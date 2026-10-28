#!/usr/bin/env python3
"""M1-A isolated personal-reservation demo and real D/M1 submission contract checks. No M2/M3 business fixtures."""
import argparse
import base64
import copy
import http.cookiejar
import urllib.error
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
import getpass
import http.client
import json
import os
from pathlib import Path
import secrets
import time
import urllib.request

from check_m1_http import ROOT, DEPT, BrowserHTTP, Process, check_ports, free_port, launch, period, seed, update_resource


def checks(admin, users, data, d_base, m_base, closed_base, m1, origin, password):
    done = []
    student = BrowserHTTP(d_base)
    student.login('student', password)
    start, end = period()
    def check(name, action):
        action()
        done.append(name)
        print('PASS '+name, flush=True)
    def equal(a,b):
        assert a == b, 'contract mismatch (private payload omitted)'
    def token(browser):
        return browser.request('/api/m1/v1/csrf', base=m_base)[1]['token']
    def payload(room=None, seat=None, shift=0):
        when=datetime.fromisoformat(start.replace('Z','+00:00'))+timedelta(hours=shift)
        if when.astimezone(timezone(timedelta(hours=8))).hour==23:
            when+=timedelta(hours=1)
        return dict(roomId=(room or data['room'])['id'], seatId=(seat or data['seats'][0])['id'],
                    startAt=when.isoformat().replace('+00:00','Z'),endAt=(when+timedelta(hours=1)).isoformat().replace('+00:00','Z'))
    def post(browser,body,key,status=201,csrf=None,**overrides):
        headers={'Origin':origin,'Idempotency-Key':key,'X-M1-CSRF-Token':csrf or token(browser)}
        headers.update(overrides)
        return browser.request('/api/m1/v1/reservations','POST',body,status,base=m_base,headers=headers)[1]
    def own(browser,path,status=200):
        return browser.request('/api/m1/v1/'+path,base=m_base,status=status)[1]
    def user(name,roles):
        value=admin.request('/api/v1/users','POST',dict(username=name,displayName='本地虚构用户',departmentId=DEPT,roles=roles,password=password),201)[1]
        browser=BrowserHTTP(d_base);browser.login(name,password)
        return browser,value
    initial=m1.command({'op':'snapshot'})
    check('ordinary M1 stays read-only',lambda:student.request('/api/m1/v1/reservations','POST',payload(),405,base=closed_base))
    student.request('/api/m1/v1/csrf',status=404,base=closed_base)
    done.append('ordinary M1 has no CSRF write endpoint');print('PASS '+done[-1])
    check('demo capability never claims production availability',lambda:equal(own(student,'reservation-capabilities')['productionReady'],False))
    csrf=token(student)
    check('missing CSRF -> 403',lambda:student.request('/api/m1/v1/reservations','POST',payload(),403,base=m_base,headers={'Origin':origin,'Idempotency-Key':'csrf-missing'}))
    check('wrong origin -> 403',lambda:post(student,payload(),'wrong-origin',403,csrf,Origin='http://invalid.example'))
    check('cross-site metadata -> 403',lambda:post(student,payload(),'cross-site',403,csrf,**{'Sec-Fetch-Site':'cross-site'}))
    manager=BrowserHTTP(d_base);manager.login('manager',password)
    check('D management permission is not reservation permission',lambda:manager.request('/api/m1/v1/csrf',status=403,base=m_base))
    check('denials leave all M1 rows unchanged',lambda:equal(m1.command({'op':'snapshot'}),initial))
    created=post(student,payload(),'first')
    check('create confirmed personal demo reservation',lambda:equal((created['status'],created['stage'],created['productionReady']),('CONFIRMED','PERSONAL_DEMO',False)))
    saved=m1.command({'op':'snapshot'})
    check('same request replays original ID',lambda:equal(post(student,payload(),'first',200)['id'],created['id']))
    check('replay does not duplicate any M1 row or event',lambda:equal(m1.command({'op':'snapshot'}),saved))
    check('same key different seat -> 409',lambda:post(student,payload(seat=data['seats'][1]),'first',409))
    check('read own reservation by ID',lambda:equal(own(student,'reservations/'+created['id'])['id'],created['id']))
    check('recover own committed request',lambda:equal(own(student,'reservation-requests/first')['id'],created['id']))
    peer,peer_identity=user('peer',['SEAT_USER'])
    check('other user cannot read reservation ID',lambda:own(peer,'reservations/'+created['id'],404))
    check('other user cannot recover foreign request key',lambda:own(peer,'reservation-requests/first',404))
    check('session-bound CSRF cannot be borrowed',lambda:post(peer,payload(),'borrowed',403,csrf))
    check('occupied seat -> 409',lambda:post(peer,payload(),'occupied',409))
    check('own overlapping slot -> 409',lambda:post(student,payload(seat=data['seats'][1]),'own-overlap',409))
    admin.request('/api/v1/roles','POST',dict(code='READ_TEST',name='测试本人读取',permissions=['seats.reservations.read.own']),201)
    u=users['student']
    admin.request('/api/v1/users/'+u['id'],'PUT',dict(displayName=u['displayName'],departmentId=DEPT,roles=['READ_TEST'],active=True))
    check('revoked creation permission rejects replay',lambda:student.request('/api/m1/v1/csrf',status=403,base=m_base))
    check('own-read permission still permits recovery after create revocation',lambda:equal(own(student,'reservation-requests/first')['id'],created['id']))
    admin.request('/api/v1/users/'+u['id'],'PUT',dict(displayName=u['displayName'],departmentId=DEPT,roles=['SEAT_USER'],active=True))
    update_resource(admin,data['room'],active=False)
    check('new request observes disabled resource',lambda:post(peer,payload(seat=data['seats'][1]),'disabled',409))
    check('replay does not re-create a disabled-resource booking',lambda:equal(post(student,payload(),'first',200)['id'],created['id']))
    update_resource(admin,data['room'],active=True)
    cross=admin.request('/api/v1/resources?type=SEAT&parentId='+data['cross']['id'])[1]['items'][0]
    a,_=user('racer_a',['SEAT_USER']);b,_=user('racer_b',['SEAT_USER'])
    body=payload(data['cross'],cross,shift=2)
    def race(browser,key):
        headers={'Origin':origin,'Idempotency-Key':key,'X-M1-CSRF-Token':token(browser)}
        request=urllib.request.Request(m_base+'/api/m1/v1/reservations',method='POST',data=json.dumps(body).encode(),headers=dict(headers,**{'Content-Type':'application/json'}))
        try:response=browser.opener.open(request,timeout=8)
        except urllib.error.HTTPError as e:response=e
        with response:return response.status,json.load(response)
    with ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(lambda pair:race(*pair),[(a,'race-a'),(b,'race-b')]))
    check('two real HTTP clients racing for one seat: exactly one 201',lambda:equal(sorted(x[0] for x in results),[201,409]))
    check('race loser reports actual seat conflict',lambda:equal(next(x[1]['code'] for x in results if x[0]==409),'SEAT_TIME_CONFLICT'))
    body=payload(seat=data['seats'][2],shift=4)
    with ThreadPoolExecutor(max_workers=2) as pool:
        # Independent cookie jars with the same D session, so no shared CookieJar mutation across threads.
        same=BrowserHTTP(d_base)
        same.jar= http.cookiejar.CookieJar()
        for cookie in peer.jar:same.jar.set_cookie(copy.copy(cookie))
        same.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(same.jar))
        results=list(pool.map(lambda pair:race(*pair),[(peer,'same-key'),(same,'same-key')]))
    check('concurrent same request is 201 then 200',lambda:equal(sorted(x[0] for x in results),[200,201]))
    check('concurrent replay returns identical reservation ID',lambda:equal(results[0][1]['id'],results[1][1]['id']))
    # Receive only headers, discard the JSON body, then recover using the original request key.
    body=payload(seat=data['seats'][3],shift=6);parsed=urllib.parse.urlparse(m_base)
    connection=http.client.HTTPConnection(parsed.hostname,parsed.port,timeout=8)
    connection.request('POST','/api/m1/v1/reservations',body=json.dumps(body),headers={'Content-Type':'application/json','Origin':origin,'Cookie':peer.cookie(),'Idempotency-Key':'lost-body','X-M1-CSRF-Token':token(peer)})
    response=connection.getresponse();assert response.status==201;response.close();connection.close()
    recovered=own(peer,'reservation-requests/lost-body')
    check('lost response body recovers the committed record',lambda:equal(post(peer,body,'lost-body',200)['id'],recovered['id']))
    before=m1.command({'op':'snapshot'})
    bad=dict(payload(shift=8),userId=peer_identity['id'])
    check('forged identity fields -> 400',lambda:post(peer,bad,'forged',400))
    check('malformed submission does not mutate committed rows',lambda:equal(m1.command({'op':'snapshot'}),before))
    # History and repeat context remain read-only; actual repeated booking reuses the existing protected POST.
    second=post(student,payload(shift=10),'history-new')
    history_snapshot=m1.command({'op':'snapshot'})
    first_page=own(student,'reservations?size=1')
    check('own history first page is bounded',lambda:equal(len(first_page['items']),1))
    next_page=own(student,'reservations?size=1&cursor='+first_page['nextCursor'])
    check('history cursor returns distinct own rows only',lambda:equal({first_page['items'][0]['id'],next_page['items'][0]['id']},{created['id'],second['id']}))
    check('history final cursor is empty',lambda:equal(next_page['nextCursor'],''))
    check('foreign cursor cannot select another user history',lambda:own(peer,'reservations?cursor='+first_page['nextCursor'],400))
    for params in ('userId='+peer_identity['id'],'size=51','size=1&size=2','cursor=bad'):
        own(student,'reservations?'+params,400)
    done.append('history rejects identity override and invalid paging');print('PASS '+done[-1])
    check('management permission cannot read own M1 history',lambda:own(manager,'reservations',403))
    context=own(student,'reservations/'+created['id']+'/repeat-context')
    check('repeat context keeps original seat and requires new times',lambda:equal((context['room']['id'],context['seat']['id'],context['requiresNewTime'],context['canReserve'], 'startAt' in context),(payload()['roomId'],payload()['seatId'],True,False,False)))
    check('foreign user cannot load repeat context',lambda:own(peer,'reservations/'+created['id']+'/repeat-context',404))
    admin.request('/api/v1/users/'+u['id'],'PUT',dict(displayName=u['displayName'],departmentId=DEPT,roles=['READ_TEST'],active=True))
    check('read-only owner can still list history',lambda:equal(len(own(student,'reservations')['items']),2))
    check('repeat context requires current query permission',lambda:own(student,'reservations/'+created['id']+'/repeat-context',403))
    admin.request('/api/v1/users/'+u['id'],'PUT',dict(displayName=u['displayName'],departmentId=DEPT,roles=['SEAT_USER'],active=True))
    update_resource(admin,data['room'],active=False)
    check('history survives current resource disable',lambda:equal(len(own(student,'reservations')['items']),2))
    check('repeat context observes current resource disable',lambda:own(student,'reservations/'+created['id']+'/repeat-context',409))
    update_resource(admin,data['room'],active=True)
    check('history and repeat reads leave complete M1 rows unchanged',lambda:equal(m1.command({'op':'snapshot'}),history_snapshot))
    repeated=post(student,payload(shift=12),'history-repeat')
    check('repeated booking gets a new ID and preserves original',lambda:equal((repeated['id']!=created['id'],own(student,'reservations/'+created['id'])['id']),(True,created['id'])))
    return done


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--demo',action='store_true')
    parser.add_argument('--d-port',type=int,default=18180);parser.add_argument('--m1-port',type=int,default=18181);parser.add_argument('--web-port',type=int,default=5274)
    args=parser.parse_args();dport,mport=(args.d_port,args.m1_port) if args.demo else (free_port(),free_port())
    while dport==mport:mport=free_port()
    webport=args.web_port;check_ports([dport,mport]+([webport] if args.demo else []))
    # localhost keeps browser cookies separate from the previous 127.0.0.1:5174 demonstration.
    origin=f'http://localhost:{webport}'
    password=getpass.getpass('设置本次预约演示临时密码（不回显）：') if args.demo else secrets.token_urlsafe(24)
    if not 12<=len(password.encode())<=72:raise RuntimeError('密码须为12～72字节')
    env={k:v for k,v in os.environ.items() if not k.startswith(('M1_','SPRING_','DATABASE_','BOOTSTRAP_','COOKIE_')) and k not in ('PORT','M2_SERVICE_KEY','M3_SERVICE_KEY')}
    key=secrets.token_urlsafe(40);csrf_secret=base64.b64encode(secrets.token_bytes(32)).decode()
    env.update(BOOTSTRAP_ADMIN_PASSWORD=password,M1_SERVICE_KEY=key,M1_D_BASE_URL=f'http://127.0.0.1:{dport}',M1_MAX_ADVANCE='PT24H',M1_MAX_DURATION='PT4H',M1_PERSONAL_DEMO='true',M1_DEMO_ORIGIN=origin,M1_DEMO_CSRF_SECRET=csrf_secret)
    logs=ROOT/'tmp'/('m1-submit-'+str(time.time_ns()));logs.mkdir(parents=True)
    with ExitStack() as stack:
        d=launch(stack,'platform',dport,env,logs);m1=launch(stack,'seats',mport,env,logs)
        d_base=f'http://127.0.0.1:{dport}';m_base=f'http://127.0.0.1:{mport}'
        admin,users,data=seed(d_base,password,many=False)
        if args.demo:
            web_env={k:v for k,v in env.items() if not k.startswith(('M1_','BOOTSTRAP_'))};web_env.update(D_PROXY_TARGET=d_base,M1_PROXY_TARGET=m_base)
            web=Process(['npm','run','dev','--','--host','127.0.0.1','--port',str(webport)],web_env,logs/'web.log',ROOT/'apps/web',fixture=False);stack.callback(web.close)
            for _ in range(100):
                try:urllib.request.build_opener(urllib.request.ProxyHandler({})).open(origin,timeout=1).close();break
                except OSError:
                    if web.process.poll() is not None:raise RuntimeError('前端启动失败')
                    time.sleep(.1)
            else:raise RuntimeError('前端启动超时')
            print(f'预约演示已就绪：{origin}/seat-reservation-demo\n账号 student，密码为刚才终端设置值。仅个人预约演示，整教室申请/维修尚未接入；正式预约关闭。\n使用localhost以隔离旧127.0.0.1演示Cookie。Ctrl+C只清理本次临时服务/PG。',flush=True)
            while all(p.process.poll() is None for p in (d,m1,web)):time.sleep(1)
            raise RuntimeError('演示服务意外退出')
        closed_port=free_port();closed=launch(stack,'seats',closed_port,env,logs,M1_PERSONAL_DEMO='false')
        done=checks(admin,users,data,d_base,m_base,f'http://127.0.0.1:{closed_port}',m1,origin,password)
        browser=BrowserHTTP(d_base);browser.login('student',password);token=browser.request('/api/m1/v1/csrf',base=m_base)[1]['token']
        m1.command({'op':'stopDatabase'})
        browser.request('/api/m1/v1/reservations','POST',dict(roomId=data['room']['id'],seatId=data['seats'][0]['id'],startAt=period()[0],endAt=period()[1]),503,base=m_base,headers={'Origin':origin,'X-M1-CSRF-Token':token,'Idempotency-Key':'storage-down'})
        done.append('database failure -> 503, never success');print('PASS '+done[-1])
        browser.request('/api/m1/v1/reservations',base=m_base,status=503)
        done.append('history database failure -> 503, not empty');print('PASS '+done[-1])
        d.close();browser.request('/api/m1/v1/reservation-requests/first',base=f'http://127.0.0.1:{closed_port}',status=503)
        done.append('D offline -> 503 even for own reads');print('PASS '+done[-1])
        browser.request('/api/m1/v1/reservations',base=f'http://127.0.0.1:{closed_port}',status=503)
        done.append('history D offline -> 503, not empty');print('PASS '+done[-1])
        for file in logs.glob('*.log'):
            text=file.read_text()
            if any(secret in text for secret in (password,key,csrf_secret)):raise RuntimeError('credential unexpectedly present in fixture logs')
        print(json.dumps({'real_submission_checks':len(done),'result':'passed','business_modules_implemented':['M1-A'],'d':'existing real service','m2_m3_business_implemented':False,'browser_checked':False}))


if __name__=='__main__':
    try:main()
    except KeyboardInterrupt:print('\n已关闭本次预约隔离演示。')
