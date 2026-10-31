#!/usr/bin/env python3
"""Run the packaged ordinary M1 jar against a separate database and real D. No demo Spring configuration."""
import argparse
import base64
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
import getpass
import json
import os
from pathlib import Path
import secrets
import socket
import time
import urllib.error
import urllib.request
import zipfile

from check_m1_http import ROOT, BrowserHTTP, Process, check_ports, free_port, launch, seed
from check_m1_submit import checks


def start_jar(stack, env, port, logs, name):
    java=str(Path(env['JAVA_HOME'])/'bin/java') if env.get('JAVA_HOME') else 'java'
    jar=ROOT/'services/seats/target/seats-0.1.0-SNAPSHOT.jar'
    with zipfile.ZipFile(jar) as archive:
        assert not any('DemoFixtureConfiguration' in p or p.endswith('/HttpFixture.class') or p.endswith('/DatabaseFixture.class') for p in archive.namelist())
    child=dict(env,M1_PORT=str(port),M1_ADDRESS='127.0.0.1')
    process=Process([java,'-jar',str(jar)],child,logs/(name+'.log'),fixture=False);stack.callback(process.close)
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for _ in range(300):
        if process.process.poll() is not None:raise RuntimeError('普通M1应用启动失败；检查本地诊断日志')
        try:opener.open(f'http://127.0.0.1:{port}/api/m1/v1/reservations',timeout=.5)
        except urllib.error.HTTPError as error:
            if error.code==401:return process
        except OSError:pass
        time.sleep(.1)
    raise RuntimeError('普通M1应用启动超时')


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--preview',action='store_true')
    parser.add_argument('--preflight',action='store_true',help=argparse.SUPPRESS)
    parser.add_argument('--d-port',type=int,default=18280);parser.add_argument('--m1-port',type=int,default=18281);parser.add_argument('--web-port',type=int,default=5374)
    args=parser.parse_args();dport,mport=(args.d_port,args.m1_port) if args.preview else (free_port(),free_port())
    while mport==dport:mport=free_port()
    check_ports([dport,mport])
    # IPv6 loopback separates browser cookies from both old localhost and old 127.0.0.1 demonstrations.
    origin=f'http://[::1]:{args.web_port}'
    if args.preview:
        with socket.socket(socket.AF_INET6) as probe:probe.bind(('::1',args.web_port))
    if args.preflight:
        print(f'预览端口可用：D {dport} / M1 {mport} / Web {args.web_port}')
        return
    password=getpass.getpass('设置本次个人预约预览临时密码（不回显）：') if args.preview else secrets.token_urlsafe(24)
    if not 12<=len(password.encode())<=72:raise RuntimeError('密码须为12～72字节')
    env={k:v for k,v in os.environ.items()
         if not k.startswith(('M1_','SPRING_','DATABASE_','BOOTSTRAP_','COOKIE_','LOGGING_'))
         and k not in ('PORT','M2_SERVICE_KEY','M3_SERVICE_KEY','DEBUG','TRACE')}
    key=secrets.token_urlsafe(40);csrf=base64.b64encode(secrets.token_bytes(32)).decode();checkin=base64.b64encode(secrets.token_bytes(32)).decode()
    env.update(BOOTSTRAP_ADMIN_PASSWORD=password,M1_D_BASE_URL=f'http://127.0.0.1:{dport}',M1_SERVICE_KEY=key,
               M1_ADVANCE_MODE='CALENDAR_DAYS',M1_MAX_ADVANCE='PT24H',M1_MAX_DURATION='PT4H',M1_WRITE_ENABLED='true',M1_BROWSER_ORIGIN=origin,M1_CSRF_SECRET=csrf,M1_CHECKIN_SECRET=checkin,
               M1_LIFECYCLE_ENABLED='true')
    logs=ROOT/'tmp'/('m1-personal-'+str(time.time_ns()));logs.mkdir(parents=True)
    with ExitStack() as stack:
        folder=ROOT/'services/seats';cp=os.pathsep.join([str(folder/'target/test-classes'),str(folder/'target/classes'),(folder/'target/http-classpath.txt').read_text(encoding='utf-8').strip()])
        java=str(Path(env['JAVA_HOME'])/'bin/java') if env.get('JAVA_HOME') else 'java'
        database=Process([java,'-cp',cp,'io.campusflow.seats.DatabaseFixture'],env,logs/'database.log');stack.callback(database.close)
        env.update(M1_DATABASE_URL=database.command({'op':'connection'})['url'],M1_DATABASE_USER='postgres',M1_DATABASE_PASSWORD='')
        d=launch(stack,'platform',dport,env,logs);personal=start_jar(stack,env,mport,logs,'personal')
        d_base=f'http://127.0.0.1:{dport}';m_base=f'http://127.0.0.1:{mport}'
        admin,users,data=seed(d_base,password,many=False)
        if args.preview:
            student=BrowserHTTP(d_base);student.login('student',password)
            preview_start=(datetime.now(timezone(timedelta(hours=8)))+timedelta(days=1)).replace(hour=22,minute=0,second=0,microsecond=0)
            preview_body=dict(roomId=data['room']['id'],seatId=data['seats'][0]['id'],
                              startAt=preview_start.astimezone(timezone.utc).isoformat().replace('+00:00','Z'),
                              endAt=(preview_start+timedelta(hours=1)).astimezone(timezone.utc).isoformat().replace('+00:00','Z'))
            preview_csrf=student.request('/api/m1/v1/csrf',base=m_base)[1]['token']
            preview_reservation=student.request('/api/m1/v1/reservations','POST',preview_body,201,base=m_base,
                headers={'Origin':origin,'Idempotency-Key':'preview-ready-check-in','X-M1-CSRF-Token':preview_csrf})[1]
            web_env={k:v for k,v in env.items() if not k.startswith(('M1_','BOOTSTRAP_'))};web_env.update(D_PROXY_TARGET=d_base,M1_PROXY_TARGET=m_base)
            web=Process(['npm','run','dev','--','--host','::1','--port',str(args.web_port),'--strictPort'],web_env,logs/'web.log',ROOT/'apps/web',fixture=False);stack.callback(web.close)
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            for _ in range(100):
                try:opener.open(origin,timeout=1).close();break
                except OSError:
                    if web.process.poll() is not None:raise RuntimeError('个人预约前端启动失败')
                    time.sleep(.1)
            else:raise RuntimeError('个人预约前端启动超时')
            print(f'普通M1个人预约/签到预览：{origin}/my-reservations\n学生账号student、空间管理员账号manager，密码均为终端设置值。\n已为student准备一条A101预约；须在预约开始后15分钟内签到，正确签到后进入计时与退座页面。\nmanager可在空间资源的A101详情打开签到码显示页。M1以生产jar运行，数据库为独立临时数据；整室申请/维修限制尚未接入。\n使用[::1]隔离旧演示Cookie；保留终端，Ctrl+C只清理本次预览。',flush=True)
            while all(p.process.poll() is None for p in (personal,d,web,database)):time.sleep(1)
            raise RuntimeError('预览服务意外退出')
        closed_port=free_port();closed_env=dict(env,M1_WRITE_ENABLED='false',M1_PERSONAL_DEMO='true')
        closed=start_jar(stack,closed_env,closed_port,logs,'closed')
        done=checks(admin,users,data,d_base,m_base,f'http://127.0.0.1:{closed_port}',database,origin,password,stage='PERSONAL')
        student=BrowserHTTP(d_base);student.login('student',password)
        def check(name,action):action();done.append(name);print('PASS '+name,flush=True)
        def equal(a,b):assert a==b,'ordinary personal contract mismatch (private payload omitted)'
        def own(path,status=200):return student.request('/api/m1/v1/'+path,base=m_base,status=status)[1]
        def post(body,key,token=None,status=201):return student.request('/api/m1/v1/reservations','POST',body,status,base=m_base,headers={'Origin':origin,'Idempotency-Key':key,'X-M1-CSRF-Token':token or own('csrf')['token']})[1]
        caps=own('reservation-capabilities')
        check('ordinary jar exposes personal capability, not fixture capability',lambda:equal((caps['stage'],caps['canCreateReservation'],caps['canCreateDemoReservation'],caps['uncheckedSources']),('PERSONAL',True,False,['M2','M3'])))
        check('calendar policy is configured and exposed',lambda:equal((caps['advanceMode'],caps['advanceDays'],caps['durationHours']),('CALENDAR_DAYS',1,4)))
        tomorrow=datetime.now(timezone(timedelta(hours=8))).replace(hour=22,minute=0,second=0,microsecond=0)+timedelta(days=1)
        body=dict(roomId=data['room']['id'],seatId=data['seats'][0]['id'],startAt=tomorrow.astimezone(timezone.utc).isoformat().replace('+00:00','Z'),endAt=(tomorrow+timedelta(hours=1)).astimezone(timezone.utc).isoformat().replace('+00:00','Z'))
        saved=post(body,'calendar-tomorrow')
        check('ordinary jar permits tomorrow before day boundary',lambda:equal(saved['status'],'CONFIRMED'))
        display=admin.request('/api/m1/v1/rooms/'+data['room']['id']+'/check-in-code',base=m_base)[1]
        check('authorized room display returns a six digit Shanghai daily code',lambda:equal((display['roomId'],len(display['code']),display['timeZone']),(data['room']['id'],6,'Asia/Shanghai')))
        checkin_token=own('csrf')['token']
        checked=student.request('/api/m1/v1/reservations/'+saved['id']+'/check-in','POST',{'code':display['code']},base=m_base,headers={'Origin':origin,'X-M1-CSRF-Token':checkin_token})[1]
        check('ordinary jar checks in the authenticated owner without a time window',lambda:equal((checked['status'],checked['version'],checked['idempotent']),('CHECKED_IN',2,False)))
        replayed_checkin=student.request('/api/m1/v1/reservations/'+saved['id']+'/check-in','POST',{'code':'invalid'},base=m_base,headers={'Origin':origin,'X-M1-CSRF-Token':checkin_token})[1]
        check('repeated owner check-in is idempotent without another transition',lambda:equal((replayed_checkin['status'],replayed_checkin['version'],replayed_checkin['idempotent']),('CHECKED_IN',2,True)))
        after=(tomorrow+timedelta(days=1)).replace(hour=0);bad=dict(body,startAt=after.astimezone(timezone.utc).isoformat().replace('+00:00','Z'),endAt=(after+timedelta(hours=1)).astimezone(timezone.utc).isoformat().replace('+00:00','Z'))
        check('ordinary jar rejects day after tomorrow start',lambda:equal(post(bad,'calendar-too-far',status=400)['code'],'ADVANCE_WINDOW'))
        old_token=own('csrf')['token'];before=database.command({'op':'snapshot'});personal.close()
        env['M1_CSRF_SECRET']=base64.b64encode(secrets.token_bytes(32)).decode()
        personal=start_jar(stack,env,mport,logs,'restarted')
        check('normal application restart preserves all M1 rows',lambda:equal(database.command({'op':'snapshot'}),before))
        check('old CSRF is rejected after secret rotation',lambda:post(body,'calendar-tomorrow',token=old_token,status=403))
        check('same key recovers original booking after normal restart',lambda:equal(post(body,'calendar-tomorrow',status=200)['id'],saved['id']))
        database.command({'op':'stopDatabase'})
        check('ordinary jar database failure -> 503',lambda:own('reservations',503))
        d.close();check('ordinary jar D offline -> 503',lambda:own('reservation-capabilities',503))
        protected={'preview password':password,'service key':key,'CSRF secret':csrf,'check-in secret':checkin}
        for file in logs.glob('*.log'):
            content=file.read_text(encoding='utf-8',errors='replace')
            leaked=[label for label,secret in protected.items() if secret in content]
            if leaked:raise RuntimeError(f'{file.name} unexpectedly contains protected diagnostics: {", ".join(leaked)}')
        print(json.dumps({'ordinary_jar_checks':len(done),'result':'passed','m1_entrypoint':'java -jar; no test configuration','advance_mode':'CALENDAR_DAYS','m2_m3_integrated':False,'browser_checked':False}))


if __name__=='__main__':
    try:main()
    except KeyboardInterrupt:print('\n已关闭本次个人预约预览。')
