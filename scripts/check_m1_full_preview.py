#!/usr/bin/env python3
"""Start one isolated browser preview covering the complete implemented M1 workflow."""
import argparse
import base64
from contextlib import ExitStack
import getpass
import os
from pathlib import Path
import secrets
import socket
import time
import urllib.request

from check_m1_http import ROOT, DEPT, Process, check_ports, launch, seed
from check_m1_personal import start_jar
from check_m1_reminders import SmtpCapture, wait_until


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--preflight',action='store_true',help=argparse.SUPPRESS)
    parser.add_argument('--d-port',type=int,default=18680);parser.add_argument('--m1-port',type=int,default=18681)
    parser.add_argument('--web-port',type=int,default=5674);parser.add_argument('--mailbox-port',type=int,default=8027)
    args=parser.parse_args();check_ports([args.d_port,args.m1_port,args.mailbox_port])
    with socket.socket(socket.AF_INET6) as probe:probe.bind(('::1',args.web_port))
    if args.preflight:
        print(f'全功能预览端口可用：D {args.d_port} / M1 {args.m1_port} / Web {args.web_port} / 模拟邮箱 {args.mailbox_port}')
        return
    password=getpass.getpass('设置本次 M1 全功能预览临时密码（不回显）：')
    if not 12<=len(password.encode())<=72:raise RuntimeError('密码须为12～72字节')
    origin=f'http://[::1]:{args.web_port}';capture=SmtpCapture(fail_first=False);capture.start_viewer(args.mailbox_port)
    env={k:v for k,v in os.environ.items() if not k.startswith(('M1_','SPRING_','DATABASE_','BOOTSTRAP_','COOKIE_','LOGGING_','PLATFORM_SMTP_','PLATFORM_MAIL_')) and k not in ('PORT','M2_SERVICE_KEY','M3_SERVICE_KEY','DEBUG','TRACE')}
    env.update(BOOTSTRAP_ADMIN_PASSWORD=password,M1_D_BASE_URL=f'http://127.0.0.1:{args.d_port}',M1_SERVICE_KEY=secrets.token_urlsafe(40),
               M1_ADVANCE_MODE='CALENDAR_DAYS',M1_MAX_ADVANCE='PT24H',M1_MAX_DURATION='PT4H',M1_WRITE_ENABLED='true',M1_BROWSER_ORIGIN=origin,
               M1_CSRF_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),M1_CHECKIN_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),
               M1_REMINDERS_ENABLED='true',M1_REMINDER_DELAY='200',M1_REMINDER_INITIAL_DELAY='200',M1_REMINDER_RETRY_BASE='PT0.2S',M1_REMINDER_RETRY_MAX='PT2S',
               M1_LIFECYCLE_ENABLED='true',M1_LIFECYCLE_DELAY='200',M1_LIFECYCLE_INITIAL_DELAY='200',
               PLATFORM_MAIL_ENABLED='true',PLATFORM_EMAIL_DELIVERY_DELAY_MS='200',PLATFORM_DELIVERY_DELAY_MS='200',
               PLATFORM_SMTP_HOST='127.0.0.1',PLATFORM_SMTP_PORT=str(capture.port),PLATFORM_MAIL_FROM='campusflow@example.test',
               PLATFORM_SMTP_STARTTLS='false',PLATFORM_SMTP_SSL='false',PLATFORM_SMTP_USERNAME='',PLATFORM_SMTP_PASSWORD='')
    logs=ROOT/'tmp'/('m1-full-preview-'+str(time.time_ns()));logs.mkdir(parents=True)
    try:
        with ExitStack() as stack:
            folder=ROOT/'services/seats';classpath=os.pathsep.join([str(folder/'target/test-classes'),str(folder/'target/classes'),(folder/'target/http-classpath.txt').read_text(encoding='utf-8').strip()])
            java=str(Path(env['JAVA_HOME'])/'bin/java') if env.get('JAVA_HOME') else 'java'
            database=Process([java,'-cp',classpath,'io.campusflow.seats.DatabaseFixture'],env,logs/'database.log');stack.callback(database.close)
            env.update(M1_DATABASE_URL=database.command({'op':'connection'})['url'],M1_DATABASE_USER='postgres',M1_DATABASE_PASSWORD='')
            d=launch(stack,'platform',args.d_port,env,logs);m1=start_jar(stack,env,args.m1_port,logs,'full-preview')
            d_base=f'http://127.0.0.1:{args.d_port}';m_base=f'http://127.0.0.1:{args.m1_port}'
            admin,users,resources=seed(d_base,password,many=False,student_email='student@example.test')
            admin.request('/api/v1/roles','POST',dict(code='SEAT_OPERATOR',name='座位预约运营',permissions=['seats.operations.read']),201)
            admin.request('/api/v1/users','POST',dict(username='operator',displayName='本地运营人员',email='operator@example.invalid',departmentId=DEPT,roles=['MEMBER','SEAT_OPERATOR'],password=password),201)
            fixture=database.command({'op':'seedFullPreview','userId':users['student']['id'],'seatIds':[seat['id'] for seat in resources['seats']],
                                      'roomId':resources['room']['id'],'outsideRoomId':resources['cross']['id'],'outsideSeatId':resources['crossSeat']['id']})
            database.command({'op':'triggerReminderPair','reservationId':fixture['activeReservationId']})
            web_env={k:v for k,v in env.items() if not k.startswith(('M1_','BOOTSTRAP_','DATABASE_','SPRING_','PLATFORM_SMTP_','PLATFORM_MAIL_'))};web_env.update(D_PROXY_TARGET=d_base,M1_PROXY_TARGET=m_base)
            web=Process(['npm','run','dev','--','--host','::1','--port',str(args.web_port),'--strictPort'],web_env,logs/'web.log',ROOT/'apps/web',fixture=False);stack.callback(web.close)
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            for _ in range(100):
                try:opener.open(origin,timeout=1).close();break
                except OSError:
                    if web.process.poll() is not None:raise RuntimeError('M1 全功能前端启动失败；请检查本次隔离日志')
                    time.sleep(.1)
            else:raise RuntimeError('M1 全功能前端启动超时')
            wait_until(lambda:len(capture.parsed())>=2,20)
            room_id=resources['room']['id']
            print(f'''M1 全功能隔离预览已就绪：
  学生端：{origin}/my-reservations  （账号 student）
  教室签到码：{origin}/room-checkin-display/{room_id}  （账号 manager）
  运营看板：{origin}/seat-operations  （账号 operator）
  模拟收件箱：http://127.0.0.1:{args.mailbox_port}

三个身份密码均为刚才在终端设置的临时密码。请使用不同浏览器或不同浏览器配置文件分别登录，避免会话 Cookie 相互覆盖。
student 已准备：一条处于“开始前20分钟内”的可签到预约、一条尚未开放签到的未来预约、已完成/违约/系统终止历史；可直接验证状态与日期筛选。
模拟邮箱已为可签到预约投递两条测试提醒；时间推进与历史造数只存在于测试夹具，不修改生产规则或正常开发数据库。
保留此终端，按 Ctrl+C 会清理本次隔离环境。日志目录：{logs}''',flush=True)
            while all(process.process.poll() is None for process in (d,m1,database,web)):time.sleep(1)
            raise RuntimeError('M1 全功能预览服务意外退出')
    finally:capture.close()


if __name__=='__main__':
    try:main()
    except KeyboardInterrupt:print('\n已关闭本次 M1 全功能预览。')
