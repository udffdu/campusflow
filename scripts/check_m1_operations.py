#!/usr/bin/env python3
"""Isolated CF-M1-011 operations verification and optional browser preview."""
import argparse
import base64
from contextlib import ExitStack
import getpass
import json
import os
from pathlib import Path
import secrets
import socket
import time
import urllib.parse
import urllib.request

from check_m1_http import ROOT, DEPT, BrowserHTTP, Process, check_ports, free_port, launch, seed
from check_m1_personal import start_jar


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--preview',action='store_true')
    parser.add_argument('--d-port',type=int,default=18480);parser.add_argument('--m1-port',type=int,default=18481);parser.add_argument('--web-port',type=int,default=5474)
    args=parser.parse_args();dport,mport=(args.d_port,args.m1_port) if args.preview else (free_port(),free_port())
    while mport==dport:mport=free_port()
    check_ports([dport,mport])
    if args.preview:
        with socket.socket(socket.AF_INET6) as probe:probe.bind(('::1',args.web_port))
    password=getpass.getpass('设置本次运营预览临时密码（不回显）：') if args.preview else secrets.token_urlsafe(24)
    if not 12<=len(password.encode())<=72:raise RuntimeError('密码须为12～72字节')
    origin=f'http://[::1]:{args.web_port}'
    env={k:v for k,v in os.environ.items() if not k.startswith(('M1_','SPRING_','DATABASE_','BOOTSTRAP_','COOKIE_','LOGGING_')) and k not in ('PORT','M2_SERVICE_KEY','M3_SERVICE_KEY','DEBUG','TRACE')}
    env.update(BOOTSTRAP_ADMIN_PASSWORD=password,M1_D_BASE_URL=f'http://127.0.0.1:{dport}',M1_SERVICE_KEY=secrets.token_urlsafe(40),
               M1_ADVANCE_MODE='CALENDAR_DAYS',M1_MAX_ADVANCE='PT24H',M1_MAX_DURATION='PT4H',M1_WRITE_ENABLED='true',
               M1_BROWSER_ORIGIN=origin,M1_CSRF_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),
               M1_CHECKIN_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),M1_LIFECYCLE_ENABLED='false',M1_REMINDERS_ENABLED='false')
    logs=ROOT/'tmp'/('m1-operations-'+str(time.time_ns()));logs.mkdir(parents=True)
    with ExitStack() as stack:
        folder=ROOT/'services/seats';classpath=os.pathsep.join([str(folder/'target/test-classes'),str(folder/'target/classes'),(folder/'target/http-classpath.txt').read_text(encoding='utf-8').strip()])
        java=str(Path(env['JAVA_HOME'])/'bin/java') if env.get('JAVA_HOME') else 'java'
        database=Process([java,'-cp',classpath,'io.campusflow.seats.DatabaseFixture'],env,logs/'database.log');stack.callback(database.close)
        env.update(M1_DATABASE_URL=database.command({'op':'connection'})['url'],M1_DATABASE_USER='postgres',M1_DATABASE_PASSWORD='')
        d=launch(stack,'platform',dport,env,logs);m1=start_jar(stack,env,mport,logs,'operations')
        d_base=f'http://127.0.0.1:{dport}';m_base=f'http://127.0.0.1:{mport}'
        admin,users,resources=seed(d_base,password,many=False)
        admin.request('/api/v1/roles','POST',dict(code='SEAT_OPERATOR',name='座位预约运营',permissions=['seats.operations.read']),201)
        operator=admin.request('/api/v1/users','POST',dict(username='operator',displayName='本地运营人员',email='operator@example.invalid',departmentId=DEPT,roles=['MEMBER','SEAT_OPERATOR'],password=password),201)[1]
        fixture=database.command({'op':'seedOperations','seatIds':[seat['id'] for seat in resources['seats']],
                                  'roomId':resources['room']['id'],'outsideRoomId':resources['cross']['id'],'outsideSeatId':resources['crossSeat']['id']})
        browser=BrowserHTTP(d_base);identity=browser.login('operator',password)
        student=BrowserHTTP(d_base);student.login('student',password)
        def path(**changes):
            values={'from':fixture['from'],'to':fixture['to'],'size':2};values.update(changes)
            return '/api/m1/v1/operations/reservations?'+urllib.parse.urlencode(values)
        if args.preview:
            web_env={k:v for k,v in os.environ.items() if not k.startswith(('PLATFORM_','M1_','SPRING_','DATABASE_','BOOTSTRAP_'))};web_env.update(D_PROXY_TARGET=d_base,M1_PROXY_TARGET=m_base)
            web=Process(['npm','run','dev','--','--host','::1','--port',str(args.web_port),'--strictPort'],web_env,logs/'web.log',ROOT/'apps/web',fixture=False);stack.callback(web.close)
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            for _ in range(100):
                try:opener.open(origin,timeout=1).close();break
                except OSError:
                    if web.process.poll() is not None:raise RuntimeError('运营预览前端启动失败；请检查本次隔离日志')
                    time.sleep(.1)
            else:raise RuntimeError('运营预览前端启动超时')
            print(f'预约运营预览：{origin}/seat-operations\n运营账号 operator，密码为终端设置值。\n已准备待签到、使用中、已完成、违约和中断记录；另有一条其他院系记录用于验证隔离，不应出现在页面。\n本环境不会修改正常开发数据库；保留终端，Ctrl+C 会清理本次环境。',flush=True)
            while all(process.process.poll() is None for process in (d,m1,database,web)):time.sleep(1)
            raise RuntimeError('运营预览服务意外退出')

        completed=[]
        def check(name,action):action();completed.append(name);print('PASS '+name,flush=True)
        check('missing session -> 401',lambda:BrowserHTTP(m_base).request(path(),status=401))
        check('ordinary reservation user -> 403',lambda:student.request(path(),base=m_base,status=403))
        first=browser.request(path(),base=m_base)[1]
        def aggregate_contract():
            assert first['total']==fixture['expectedScopedTotal']==5
            assert first['summary']['checkedIn']==fixture['expectedCheckedIn']==2 and first['summary']['violations']==fixture['expectedViolations']==1
            assert abs(first['summary']['checkInRate']-2/3)<1e-9 and first['summary']['reservedDurationSeconds']>first['summary']['actualUsageSeconds']>0
            serialized=json.dumps(first);assert all(field not in serialized for field in ('userId','username','displayName','email'))
            assert first['scope']['departmentId']==DEPT and all(item['roomId']==resources['room']['id'] for item in first['items'])
        check('department scope + documented metrics + data minimization',aggregate_contract)
        def stable_pages():
            rows=list(first['items']);cursor=first['nextCursor'];summary=first['summary']
            while cursor:
                page=browser.request(path(cursor=cursor),base=m_base)[1]
                for field in ('total','byStatus','checkedIn','violations','eligible','checkInRate','reservedDurationSeconds','byRoom','bySeat'):
                    assert page['summary'][field]==summary[field]
                rows.extend(page['items']);cursor=page['nextCursor']
            assert len(rows)==5 and len({row['id'] for row in rows})==5
        check('stable keyset pagination without duplicates',stable_pages)
        check('out-of-scope room filter -> 403',lambda:browser.request(path(roomId=resources['cross']['id']),base=m_base,status=403))
        def status_filter():
            data=browser.request(path(status='NO_SHOW'),base=m_base)[1];assert data['total']==1 and data['summary']['violations']==1 and data['summary']['checkInRate']==0
        check('status filter keeps list and summary consistent',status_filter)
        d.close();check('D unavailable -> 503 instead of unscoped data',lambda:browser.request(path(),base=m_base,status=503))
        assert identity['id']==operator['id']
        print(f'CF-M1-011 isolated checks passed: {len(completed)}',flush=True)


if __name__=='__main__':main()
