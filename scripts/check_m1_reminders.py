#!/usr/bin/env python3
"""Isolated CF-M1-008/009/010 real D/M1/PostgreSQL/SMTP verification and optional preview."""
import argparse
import base64
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
from email import policy
from email.parser import BytesParser
import getpass
import html
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import socket
import socketserver
import threading
import time
import urllib.error
import urllib.request

from check_m1_http import ROOT, BrowserHTTP, Process, check_ports, free_port, launch, seed
from check_m1_personal import start_jar


class SmtpCapture:
    def __init__(self, fail_first=True):
        self.messages=[];self.lock=threading.Lock();self.failures=1 if fail_first else 0
        self.viewer=None;self.viewer_thread=None;self.closed=False
        owner=self
        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                self.wfile.write(b'220 localhost CampusFlow test SMTP\r\n');self.wfile.flush();recipient='';data=None
                while True:
                    raw=self.rfile.readline()
                    if not raw:return
                    command=raw.decode('ascii','replace').rstrip('\r\n');upper=command.upper()
                    if upper.startswith(('EHLO','HELO')):self.wfile.write(b'250-localhost\r\n250-8BITMIME\r\n250 SIZE 1048576\r\n')
                    elif upper.startswith('MAIL FROM:'):self.wfile.write(b'250 2.1.0 OK\r\n')
                    elif upper.startswith('RCPT TO:'):recipient=command.split(':',1)[1].strip('<> ');self.wfile.write(b'250 2.1.5 OK\r\n')
                    elif upper=='DATA':
                        self.wfile.write(b'354 End data with <CR><LF>.<CR><LF>\r\n');self.wfile.flush();parts=[]
                        while True:
                            line=self.rfile.readline()
                            if line==b'.\r\n':break
                            parts.append(line[1:] if line.startswith(b'..') else line)
                        data=b''.join(parts)
                        with owner.lock:
                            if owner.failures:owner.failures-=1;failed=True
                            else:owner.messages.append((recipient,data));failed=False
                        self.wfile.write(b'451 4.3.0 injected temporary failure\r\n' if failed else b'250 2.0.0 queued\r\n')
                    elif upper=='RSET':recipient='';data=None;self.wfile.write(b'250 2.0.0 reset\r\n')
                    elif upper=='QUIT':self.wfile.write(b'221 2.0.0 bye\r\n');self.wfile.flush();return
                    else:self.wfile.write(b'250 2.0.0 OK\r\n')
                    self.wfile.flush()
        self.server=socketserver.ThreadingTCPServer(('127.0.0.1',0),Handler);self.server.daemon_threads=True
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    @property
    def port(self):return self.server.server_address[1]
    def start_viewer(self,port):
        owner=self
        class Viewer(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path not in ('/','/index.html'):
                    self.send_error(404);return
                cards=[]
                for index,(recipient,message) in enumerate(reversed(owner.parsed()),1):
                    plain=message.get_body(preferencelist=('plain',)) if message.is_multipart() else message
                    content=plain.get_content() if plain else ''
                    cards.append(f'''<article><span class="tag">模拟邮件 {index}</span>
<h2>{html.escape(str(message['Subject'] or '（无主题）'))}</h2>
<p class="meta">收件人：{html.escape(recipient)}</p><pre>{html.escape(str(content))}</pre></article>''')
                body=''.join(cards) or '<div class="empty">等待提醒邮件到达……页面每 2 秒自动刷新。</div>'
                page=f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta http-equiv="refresh" content="2">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>CampusFlow 模拟收件箱</title>
<style>body{{margin:0;background:#f4f7f2;color:#173b32;font:15px/1.6 system-ui,sans-serif}}main{{max-width:920px;margin:48px auto;padding:0 24px}}header{{margin-bottom:28px}}h1{{font-size:34px;margin:0}}header p,.meta{{color:#71847c}}article,.empty{{background:#fff;border:1px solid #dfe8df;border-radius:14px;padding:24px;margin:18px 0;box-shadow:0 8px 30px #173b3210}}h2{{margin:8px 0}}.tag{{color:#24705b;font-size:12px;letter-spacing:.14em}}pre{{white-space:pre-wrap;font:inherit;background:#f7faf6;border-radius:8px;padding:16px}}</style></head>
<body><main><header><div class="tag">LOCAL SMTP PREVIEW</div><h1>CampusFlow 模拟收件箱</h1><p>仅存在于本次隔离测试，不会向真实邮箱发信。</p></header>{body}</main></body></html>'''.encode('utf-8')
                self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Content-Length',str(len(page)));self.end_headers();self.wfile.write(page)
            def log_message(self,format,*args):pass
        self.viewer=ThreadingHTTPServer(('127.0.0.1',port),Viewer);self.viewer.daemon_threads=True
        self.viewer_thread=threading.Thread(target=self.viewer.serve_forever,daemon=True);self.viewer_thread.start()
    def close(self):
        if self.closed:return
        self.closed=True
        if self.viewer:self.viewer.shutdown();self.viewer.server_close()
        if self.viewer_thread:self.viewer_thread.join(timeout=2)
        self.server.shutdown();self.server.server_close();self.thread.join(timeout=2)
    def parsed(self):
        with self.lock:return [(recipient,BytesParser(policy=policy.default).parsebytes(raw)) for recipient,raw in self.messages]


def wait_until(action,timeout=15):
    end=time.monotonic()+timeout;last=None
    while time.monotonic()<end:
        try:
            value=action()
            if value:return value
        except Exception as error:last=error
        time.sleep(.2)
    if last:raise last
    raise AssertionError('timed out waiting for reminder delivery')


def iso(value):return value.astimezone(timezone.utc).isoformat().replace('+00:00','Z')


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--preview',action='store_true');parser.add_argument('--real-mail',action='store_true')
    parser.add_argument('--no-show',action='store_true',help='自动验证超时取消、违约记录与通知投递')
    parser.add_argument('--manual-booking',action='store_true',help='等待用户在前端完成预约，再立即推进两个提醒时间点')
    parser.add_argument('--manual-no-show',action='store_true',help='等待用户在前端完成预约，再仅在隔离数据库推进至未签到超时')
    parser.add_argument('--d-port',type=int,default=18280);parser.add_argument('--m1-port',type=int,default=18281);parser.add_argument('--web-port',type=int,default=5374)
    parser.add_argument('--mailbox-port',type=int,default=8025)
    args=parser.parse_args()
    if args.real_mail and not args.preview:parser.error('--real-mail 只能与 --preview 一起使用')
    if args.manual_booking and not args.preview:parser.error('--manual-booking 只能与 --preview 一起使用')
    if args.manual_no_show and not args.preview:parser.error('--manual-no-show 只能与 --preview 一起使用')
    if args.manual_booking and args.manual_no_show:parser.error('--manual-booking 与 --manual-no-show 不能同时使用')
    if args.no_show and args.preview:parser.error('--no-show 是自动检查模式；人工预览请使用 --manual-no-show')
    if args.preview:
        dport,mport=args.d_port,args.m1_port;check_ports([dport,mport]+([] if args.real_mail else [args.mailbox_port]))
        with socket.socket(socket.AF_INET6) as probe:probe.bind(('::1',args.web_port))
    else:
        dport,mport=free_port(),free_port()
        while mport==dport:mport=free_port()
    password=getpass.getpass('设置本次提醒预览临时密码（不回显）：') if args.preview else secrets.token_urlsafe(24)
    if not 12<=len(password.encode())<=72:raise RuntimeError('密码须为12～72字节')
    capture=None if args.real_mail else SmtpCapture(fail_first=not args.preview)
    if capture and args.preview:capture.start_viewer(args.mailbox_port)
    if capture is None:
        student_email=os.environ.get('CF_REMINDER_EMAIL','').strip()
        required=['PLATFORM_SMTP_HOST','PLATFORM_SMTP_PORT','PLATFORM_MAIL_FROM']
        if not student_email or any(not os.environ.get(name) for name in required):
            raise RuntimeError('真实邮件预览需要 CF_REMINDER_EMAIL、PLATFORM_SMTP_HOST、PLATFORM_SMTP_PORT、PLATFORM_MAIL_FROM；认证信息按需设置')
    else:student_email='student@example.test'
    origin=f'http://[::1]:{args.web_port}' if args.preview else 'http://127.0.0.1:5274';key=secrets.token_urlsafe(40)
    env={k:v for k,v in os.environ.items() if not k.startswith(('M1_','SPRING_','DATABASE_','BOOTSTRAP_','COOKIE_','LOGGING_')) and k not in ('PORT','M2_SERVICE_KEY','M3_SERVICE_KEY','DEBUG','TRACE')}
    env.update(BOOTSTRAP_ADMIN_PASSWORD=password,M1_SERVICE_KEY=key,M1_D_BASE_URL=f'http://127.0.0.1:{dport}',
               M1_ADVANCE_MODE='CALENDAR_DAYS',M1_MAX_ADVANCE='PT24H',M1_MAX_DURATION='PT4H',M1_WRITE_ENABLED='true',
               M1_BROWSER_ORIGIN=origin,M1_CSRF_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),
               M1_CHECKIN_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),M1_REMINDERS_ENABLED='true',
               M1_REMINDER_DELAY='200',M1_REMINDER_INITIAL_DELAY='200',M1_REMINDER_RETRY_BASE='PT0.2S',M1_REMINDER_RETRY_MAX='PT2S',
               M1_LIFECYCLE_ENABLED='true',M1_LIFECYCLE_DELAY='200',M1_LIFECYCLE_INITIAL_DELAY='200',
               PLATFORM_MAIL_ENABLED='true',PLATFORM_EMAIL_DELIVERY_DELAY_MS='200',PLATFORM_DELIVERY_DELAY_MS='200')
    if capture:
        env.update(PLATFORM_SMTP_HOST='127.0.0.1',PLATFORM_SMTP_PORT=str(capture.port),PLATFORM_MAIL_FROM='campusflow@example.test',
                   PLATFORM_SMTP_STARTTLS='false',PLATFORM_SMTP_SSL='false',PLATFORM_SMTP_USERNAME='',PLATFORM_SMTP_PASSWORD='')
    logs=ROOT/'tmp'/('m1-reminders-'+str(time.time_ns()));logs.mkdir(parents=True)
    try:
        with ExitStack() as stack:
            if capture:stack.callback(capture.close)
            folder=ROOT/'services/seats';classpath=os.pathsep.join([str(folder/'target/test-classes'),str(folder/'target/classes'),(folder/'target/http-classpath.txt').read_text(encoding='utf-8').strip()])
            java=str(Path(env['JAVA_HOME'])/'bin/java') if env.get('JAVA_HOME') else 'java'
            database=Process([java,'-cp',classpath,'io.campusflow.seats.DatabaseFixture'],env,logs/'database.log');stack.callback(database.close)
            env.update(M1_DATABASE_URL=database.command({'op':'connection'})['url'],M1_DATABASE_USER='postgres',M1_DATABASE_PASSWORD='')
            d=launch(stack,'platform',dport,env,logs);m1=start_jar(stack,env,mport,logs,'reminders')
            d_base=f'http://127.0.0.1:{dport}';m_base=f'http://127.0.0.1:{mport}'
            _,_,resources=seed(d_base,password,many=False,student_email=student_email)
            student=BrowserHTTP(d_base);student.login('student',password)
            if args.preview:
                web_env={k:v for k,v in os.environ.items() if not k.startswith(('PLATFORM_','M1_','SPRING_','DATABASE_','BOOTSTRAP_'))}
                web_env.update(D_PROXY_TARGET=d_base,M1_PROXY_TARGET=m_base)
                web=Process(['npm','run','dev','--','--host','::1','--port',str(args.web_port),'--strictPort'],web_env,logs/'web.log',ROOT/'apps/web',fixture=False);stack.callback(web.close)
                opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
                for _ in range(100):
                    try:opener.open(origin,timeout=1).close();break
                    except OSError:
                        if web.process.poll() is not None:raise RuntimeError('提醒预览前端启动失败；请检查本次隔离日志')
                        time.sleep(.1)
                else:raise RuntimeError('提醒预览前端启动超时')
                mailbox='真实邮箱' if args.real_mail else f'模拟收件箱：http://127.0.0.1:{args.mailbox_port}'
                entry='/my-reservations' if args.manual_no_show else '/seat-reservations' if args.manual_booking else '/notifications'
                print(f'提醒预览：{origin}{entry}\n{mailbox}\n学生账号 student，密码为终端设置值。\n前端、D、M1 和数据库均为本次隔离环境；请保留终端，Ctrl+C 会清理本次环境。',flush=True)
            if args.manual_no_show:
                known={row['id'] for row in json.loads(database.command({'op':'snapshot'})['reservations'])}
                processed=0
                def advance_to_no_show(reservation):
                    nonlocal processed
                    known.add(reservation['id'])
                    print(f'已检测到预约 {reservation["id"]}，正在触发未签到超时……',flush=True)
                    database.command({'op':'makeNoShowDue','reservationId':reservation['id']})
                    def no_show_delivered(reservation_id=reservation['id']):
                        snapshot=database.command({'op':'snapshot'})
                        rows=json.loads(snapshot['reservations']);violations=json.loads(snapshot['reservation_violations'])
                        events=json.loads(snapshot['outbox_events'])
                        state=next((row for row in rows if row['id']==reservation_id),None)
                        violation_count=sum(row['reservation_id']==reservation_id for row in violations)
                        delivered=sum(event['reservation_id']==reservation_id and event['event_type']=='RESERVATION_NO_SHOW'
                                      and event['status']=='DELIVERED' for event in events)
                        return state and state['status']=='NO_SHOW' and violation_count==1 and delivered==1
                    wait_until(no_show_delivered,15)
                    processed+=1;target='真实邮箱' if args.real_mail else '模拟收件箱'
                    print(f'预约 {reservation["id"]} 已取消、座位已释放、违约与通知均已落库投递（本次共处理 {processed} 笔）。请刷新“我的预约”并检查{target}、通知弹窗。',flush=True)

                # Give the visual preview an immediately inspectable violation. This uses the
                # public reservation API first; only the disposable fixture changes its time.
                now=datetime.now(timezone.utc);regular=(now+timedelta(hours=2)).replace(minute=0,second=0,microsecond=0)
                body=dict(roomId=resources['room']['id'],seatId=resources['seats'][0]['id'],startAt=iso(regular),endAt=iso(regular+timedelta(hours=1)))
                csrf=student.request('/api/m1/v1/csrf',base=m_base)[1]['token']
                sample=student.request('/api/m1/v1/reservations','POST',body,201,base=m_base,
                                       headers={'Origin':origin,'Idempotency-Key':'no-show-visual-sample','X-M1-CSRF-Token':csrf})[1]
                advance_to_no_show(sample)
                print('红色违约示例已准备完成。请登录后进入“我的预约”；也可以继续手工创建预约，脚本会逐笔推进。',flush=True)
                while all(process.process.poll() is None for process in (d,m1,database,web)):
                    created=[row for row in json.loads(database.command({'op':'snapshot'})['reservations']) if row['id'] not in known]
                    for reservation in created:
                        advance_to_no_show(reservation)
                    time.sleep(.4)
                raise RuntimeError('未签到预览服务意外退出')
            if args.manual_booking:
                known={row['id'] for row in json.loads(database.command({'op':'snapshot'})['reservations'])}
                delivered_count=0
                print('请在“座位预约”页面正常完成预约。脚本会持续监听，每一笔新预约都会立即收到两条测试提醒。',flush=True)
                while all(process.process.poll() is None for process in (d,m1,database,web)):
                    created=[row for row in json.loads(database.command({'op':'snapshot'})['reservations']) if row['id'] not in known]
                    for reservation in created:
                        known.add(reservation['id'])
                        print(f'已检测到预约 {reservation["id"]}，正在触发两条提醒……',flush=True)
                        database.command({'op':'triggerReminderPair','reservationId':reservation['id']})
                        def pair_delivered(reservation_id=reservation['id']):
                            events=json.loads(database.command({'op':'snapshot'})['outbox_events'])
                            delivered={event['event_type'] for event in events
                                       if event['reservation_id']==reservation_id and event['status']=='DELIVERED'}
                            return {'RESERVATION_START_REMINDER','RESERVATION_LATE_REMINDER'}<=delivered
                        wait_until(pair_delivered,12)
                        delivered_count+=1
                        target='真实邮箱' if args.real_mail else '模拟收件箱'
                        print(f'预约 {reservation["id"]} 的两条提醒均已投递（本次共处理 {delivered_count} 笔）。请检查{target}与页面弹窗；可以继续创建预约。',flush=True)
                    time.sleep(.4)
                raise RuntimeError('即时提醒预览服务意外退出')
            else:
                now=datetime.now(timezone.utc);regular=(now+timedelta(hours=2)).replace(minute=0,second=0,microsecond=0)
                body=dict(roomId=resources['room']['id'],seatId=resources['seats'][0]['id'],startAt=iso(regular),endAt=iso(regular+timedelta(hours=1)))
                csrf=student.request('/api/m1/v1/csrf',base=m_base)[1]['token']
                reservation=student.request('/api/m1/v1/reservations','POST',body,201,base=m_base,headers={'Origin':origin,'Idempotency-Key':'reminder-preview','X-M1-CSRF-Token':csrf})[1]
            if args.no_show:
                database.command({'op':'makeNoShowDue','reservationId':reservation['id']})
                def no_show_state():
                    snapshot=database.command({'op':'snapshot'})
                    rows=json.loads(snapshot['reservations']);violations=json.loads(snapshot['reservation_violations'])
                    events=json.loads(snapshot['outbox_events'])
                    state=next((row for row in rows if row['id']==reservation['id']),None)
                    violation_count=sum(row['reservation_id']==reservation['id'] for row in violations)
                    delivered=[event for event in events if event['reservation_id']==reservation['id']
                               and event['event_type']=='RESERVATION_NO_SHOW' and event['status']=='DELIVERED']
                    return snapshot if state and state['status']=='NO_SHOW' and state['version']==2 and violation_count==1 and len(delivered)==1 else None
                snapshot=wait_until(no_show_state,18)
                messages=wait_until(lambda:capture.parsed() if len(capture.parsed())>=1 else None,12)
                assert len(messages)==1
                recipient,message=messages[0];plain=message.get_body(preferencelist=('plain',)) if message.is_multipart() else message
                content=str(plain.get_content())
                assert recipient==student_email and str(message['Subject']).startswith('预约已因未签到取消｜')
                assert resources['room']['code'] in content and resources['seats'][0]['code'] in content
                assert '释放座位' in content and '未签到违约' in content
                inbox=wait_until(lambda:(lambda value:value if any(item['title'].startswith('预约已因未签到取消｜') for item in value['items']) else None)(student.request('/api/v1/notifications?size=20')[1]),8)
                assert sum(row['reservation_id']==reservation['id'] for row in json.loads(snapshot['reservation_violations']))==1
                time.sleep(1);assert len(capture.parsed())==1
                print('PASS CF-M1-010: atomic no-show cancellation, occupancy release fact, one violation and durable in-app/email notification',flush=True)
                return
            early_start=datetime.now(timezone.utc)+timedelta(minutes=14)
            database.command({'op':'setPeriod','reservationId':reservation['id'],'startAt':iso(early_start),'endAt':iso(early_start+timedelta(hours=1))})
            if args.preview:
                print('已触发“开始前15分钟”提醒；请检查邮箱和学生通知中心。10秒后将隔离数据调整到“开始后10分钟”以触发第二封邮件。',flush=True);time.sleep(10)
            else:
                wait_until(lambda:len(capture.parsed())>=1,18)
            late_start=datetime.now(timezone.utc)-timedelta(minutes=10,seconds=1)
            database.command({'op':'setPeriod','reservationId':reservation['id'],'startAt':iso(late_start),'endAt':iso(late_start+timedelta(hours=1))})
            if args.preview:
                target='真实邮箱' if args.real_mail else '模拟收件箱'
                print(f'已触发“开始后10分钟仍未签到”提醒。服务保持运行，请检查{target}与学生站内通知；Ctrl+C清理本次隔离环境。',flush=True)
                while all(process.process.poll() is None for process in (d,m1,database,web)):time.sleep(1)
                raise RuntimeError('提醒预览服务意外退出')
            messages=wait_until(lambda:capture.parsed() if len(capture.parsed())>=2 else None,12)
            subjects=[str(message['Subject']) for _,message in messages];recipients=[recipient for recipient,_ in messages]
            contents=[str((message.get_body(preferencelist=('plain',)) if message.is_multipart() else message).get_content()) for _,message in messages]
            assert recipients==[student_email,student_email]
            assert sum(subject.startswith('预约即将开始｜') for subject in subjects)==1
            assert sum(subject.startswith('尚未签到｜') for subject in subjects)==1
            assert all(resources['room']['code'] in content and resources['seats'][0]['code'] in content and '时间：' in content and '上海时间' in content for content in contents)
            time.sleep(1);assert len(capture.parsed())==2
            inbox=wait_until(lambda:(lambda value:value if value['total']>=2 else None)(student.request('/api/v1/notifications?size=20')[1]),8)
            assert any(item['title'].startswith('预约即将开始｜') for item in inbox['items'])
            assert any(item['title'].startswith('尚未签到｜') for item in inbox['items'])
            assert all(resources['room']['code'] in item['body'] and resources['seats'][0]['code'] in item['body'] and '时间：' in item['body'] for item in inbox['items'][:2])
            snapshot=database.command({'op':'snapshot'});events=json.loads(snapshot['outbox_events'])
            reminders=[event for event in events if event['event_type'] in ('RESERVATION_START_REMINDER','RESERVATION_LATE_REMINDER')]
            assert len(reminders)==2 and all(event['status']=='DELIVERED' for event in reminders)
            print('PASS CF-M1-008/009: two durable reminders, in-app delivery, SMTP retry and exactly one email per trigger',flush=True)
    finally:
        if capture:
            try:capture.close()
            except Exception:pass


if __name__=='__main__':
    try:main()
    except KeyboardInterrupt:print('\n已关闭本次提醒预览。')
