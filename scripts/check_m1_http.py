#!/usr/bin/env python3
"""Actual D + M1 TCP/PG contract test, or an isolated local demonstration. No external Python dependencies."""
import argparse
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
import getpass
import http.cookiejar
import json
import os
from pathlib import Path
import queue
import secrets
import signal
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DEPT = '10000000-0000-0000-0000-000000000001'
OTHER = '10000000-0000-0000-0000-000000000002'


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def check_ports(ports):
    if len(set(ports)) != len(ports):
        raise RuntimeError('服务端口不能相同')
    for port in ports:
        with socket.socket() as sock:
            try:
                sock.bind(('127.0.0.1', port))
            except OSError:
                raise RuntimeError(f'端口 {port} 已占用；不会停止占用进程') from None


class Process:
    def __init__(self, command, env, log, cwd=ROOT, fixture=True):
        self.fixture = fixture
        self.log = open(log, 'w', encoding='utf-8', errors='replace')
        self.reply = queue.Queue()
        self.ready = threading.Event()
        child_env = dict(env)
        runtime = ROOT / 'tmp' / 'runtime'
        java_tmp = runtime / 'temp'
        pg_cache = runtime / 'embedded-pg'
        user_home = runtime / 'home'
        for directory in (java_tmp, pg_cache, user_home):
            directory.mkdir(parents=True, exist_ok=True)
        # Keep embedded PostgreSQL binaries, clusters and Java temp files inside this checkout.
        java_options = child_env.get('JAVA_TOOL_OPTIONS', '').strip()
        java_options += (' ' if java_options else '') + ' '.join((
            f'-Djava.io.tmpdir={java_tmp.as_posix()}',
            f'-Duser.home={user_home.as_posix()}',
            f'-Dot.epg.cache-dir={pg_cache.as_posix()}',
            f'-Dot.epg.working-dir={java_tmp.as_posix()}',
        ))
        child_env['JAVA_TOOL_OPTIONS'] = java_options
        self.process = subprocess.Popen(command, cwd=cwd, env=child_env, stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                        encoding='utf-8', errors='replace', bufsize=1, start_new_session=True)
        def drain():
            for line in self.process.stdout:
                # These services never log cookie/header/request bodies. Keep only local diagnostic output.
                if line.startswith('FIXTURE_REPLY '):
                    self.reply.put(json.loads(line[len('FIXTURE_REPLY '):]))
                else:
                    self.log.write(line)
                    self.log.flush()
                if line.strip() == 'FIXTURE_READY':
                    self.ready.set()
        self.reader = threading.Thread(target=drain, daemon=True)
        self.reader.start()
        if fixture and not self.ready.wait(40):
            self.close()
            raise RuntimeError('隔离服务启动失败；请查看本地日志（不含凭据）')

    def command(self, body):
        self.process.stdin.write(json.dumps(body) + '\n')
        self.process.stdin.flush()
        return self.reply.get(timeout=10)

    def close(self):
        if self.process.poll() is None:
            try:
                if self.fixture:
                    self.process.stdin.write('STOP\n')
                    self.process.stdin.flush()
                elif os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(self.process.pid), '/T', '/F'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   check=False, timeout=10)
                else:
                    os.killpg(self.process.pid, signal.SIGTERM)
                self.process.wait(timeout=8)
            except (OSError, subprocess.TimeoutExpired):
                # Only this explicitly spawned process group, including its own PG/Vite children.
                try:
                    if os.name == 'nt':
                        subprocess.run(['taskkill', '/PID', str(self.process.pid), '/T', '/F'],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       check=False, timeout=10)
                    else:
                        os.killpg(self.process.pid, signal.SIGTERM)
                except (OSError, subprocess.TimeoutExpired, ProcessLookupError):
                    pass
                try:
                    self.process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    if os.name == 'nt':
                        subprocess.run(['taskkill', '/PID', str(self.process.pid), '/T', '/F'],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       check=False, timeout=10)
                    else:
                        os.killpg(self.process.pid, signal.SIGKILL)
                    self.process.wait(timeout=5)
        self.reader.join(timeout=2)
        if not self.log.closed:
            self.log.close()


class BrowserHTTP:
    def __init__(self, base):
        self.base = base
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.jar))

    def request(self, path, method='GET', body=None, status=200, base=None, cookie=None, headers=None):
        headers = dict(headers or {})
        if method != 'GET' and path.startswith('/api/v1/'):
            _, csrf = self.request('/api/v1/auth/csrf')
            headers['X-XSRF-TOKEN'] = csrf['token']
        if body is not None:
            headers['Content-Type'] = 'application/json'
        if cookie is not None:
            headers['Cookie'] = cookie
        request = urllib.request.Request((base or self.base) + path, method=method,
                                         data=None if body is None else json.dumps(body).encode(), headers=headers)
        try:
            response = self.opener.open(request, timeout=8)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result = json.load(response)
            # Deliberately omit response payloads, credentials and identity details from assertion output.
            assert response.status == status, f'{method} {path.split("?")[0]}: expected {status}, received {response.status}'
            return response.headers, result

    def login(self, name, password):
        return self.request('/api/v1/auth/login', 'POST', {'username': name, 'password': password})[1]

    def cookie(self):
        return next('CF_SESSION=' + c.value for c in self.jar if c.name == 'CF_SESSION')


def launch(stack, module, port, env, logdir, **extra):
    folder = ROOT / 'services' / module
    classpath = folder / 'target/http-classpath.txt'
    if not classpath.exists():
        raise RuntimeError('请先运行 bash scripts/prepare-m1-http.sh')
    cp = os.pathsep.join([str(folder / 'target/test-classes'), str(folder / 'target/classes'), classpath.read_text(encoding='utf-8').strip()])
    java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else 'java'
    child = dict(env, **{k: str(v) for k, v in extra.items()})
    child.update({'PORT' if module == 'platform' else 'M1_PORT': str(port)})
    # Strip app config inherited from a user's other demo; pass this fixture's explicit settings.
    process = Process([java, '-cp', cp, f'io.campusflow.{module}.HttpFixture'], child, logdir / f'{module}-{port}.log')
    stack.callback(process.close)
    return process


def seed(base, password, many, student_email=None):
    admin = BrowserHTTP(base)
    admin.login('admin', password)
    def create_user(name, dept, roles):
        email=student_email if name=='student' and student_email else name+'@example.invalid'
        return admin.request('/api/v1/users', 'POST', dict(username=name, displayName='本地虚构用户', email=email, departmentId=dept, roles=roles, password=password), 201)[1]
    identities = {
        'student': create_user('student', DEPT, ['SEAT_USER']),
        'outsider': create_user('outsider', OTHER, ['MEMBER', 'SEAT_USER']),
        'manager': create_user('manager', DEPT, ['SPACE_MANAGER']),
    }
    def resource(kind, code, parent=None, dept=None, scope=None, features=None, hours=None):
        body = dict(type=kind, code=code, name=code + ' 本地演示', parentId=parent, departmentId=dept,
                    capacity=120 if kind == 'ROOM' else 1, features=features or [], openingHours=hours or [], active=True)
        if scope:
            body['bookingScope'] = scope
        return admin.request('/api/v1/resources', 'POST', body, 201)[1]
    campus = resource('CAMPUS', 'HTTP-CAMPUS')
    building = resource('BUILDING', 'HTTP-BUILDING', campus['id'])
    hours = [dict(day=day, start='00:00', end='23:59') for day in range(1, 8)]
    room = resource('ROOM', 'A101', building['id'], DEPT, 'DEPARTMENT', hours=hours)
    cross = resource('ROOM', 'B201', building['id'], OTHER, 'CAMPUS', hours=hours)
    private = resource('ROOM', 'B202', building['id'], OTHER, 'DEPARTMENT', hours=hours)
    closed = resource('ROOM', 'A102', building['id'], DEPT, 'DEPARTMENT')
    seats = [resource('SEAT', f'A101-S{i:03d}', room['id'], DEPT,
                      features=['POWER_OUTLET'] if i == (100 if many else 3) else ['电源']) for i in range(101 if many else 4)]
    cross_seat = resource('SEAT', 'B201-S001', cross['id'], OTHER, features=['POWER_OUTLET'])
    return admin, identities, dict(room=room, cross=cross, crossSeat=cross_seat, private=private, closed=closed, building=building, seats=seats)


def update_resource(admin, resource, **change):
    body = {k: resource[k] for k in ('name', 'capacity', 'features', 'openingHours', 'active', 'bookingScope')}
    body.update(change, expectedVersion=resource['version'])
    result = admin.request('/api/v1/resources/' + resource['id'], 'PUT', body)[1]
    resource.update(result)


def period():
    start = (datetime.now(timezone.utc) + timedelta(hours=1)).replace(minute=0, second=0, microsecond=0)
    # Keep this test away from the deliberately unsupported midnight boundary, without depending on weekday.
    local = start.astimezone(timezone(timedelta(hours=8)))
    if local.hour == 23:
        start += timedelta(hours=1)
    return start.isoformat().replace('+00:00', 'Z'), (start + timedelta(hours=1)).isoformat().replace('+00:00', 'Z')


def exercise(d_base, m_base, m1, password):
    admin, users, fixtures = seed(d_base, password, many=True)
    student = BrowserHTTP(d_base)
    identity = student.login('student', password)
    outsider = BrowserHTTP(d_base)
    outsider.login('outsider', password)
    manager = BrowserHTTP(d_base)
    manager.login('manager', password)
    start, end = period()
    snapshot = m1.command({'op': 'snapshot'})
    completed = []
    def check(name, action):
        action()
        completed.append(name)
        print('PASS ' + name, flush=True)
    def query(browser=student, room=None, power=False, status=200, **extra):
        params = dict(roomId=(room or fixtures['room'])['id'], startAt=start, endAt=end, requiresPower=str(power).lower(), **extra)
        return browser.request('/api/m1/v1/seat-candidates?' + urllib.parse.urlencode(params), base=m_base, status=status)[1]
    def equal(actual, expected):
        assert actual == expected, 'contract assertion failed (sensitive payload omitted)'
    check('session missing -> 401', lambda: BrowserHTTP(m_base).request('/api/m1/v1/rooms', status=401))
    check('spaces.write does not grant M1 -> 403', lambda: query(manager, status=403))
    def candidate_contract():
        data = query()
        equal((data['total'], len(data['items']), data['canReserve'], data['stage']), (101, 20, False, 'CANDIDATE_ONLY'))
        assert 'userId' not in json.dumps(data) and 'reservationId' not in json.dumps(data)
    check('D session + >100 D seats + bounded browser pagination', candidate_contract)
    check('exact POWER_OUTLET filtering across D pages', lambda: equal(query(power=True)['items'][0]['id'], fixtures['seats'][-1]['id']))
    check('seat candidate page 2', lambda: equal(len(query(page=1, size=100)['items']), 1))
    def room_filter():
        data = student.request('/api/m1/v1/rooms?size=1', base=m_base)[1]
        equal(data['total'], 3)
        equal(len(data['items']), 1)
    check('room total filtered by personal scope before paging', room_filter)
    check('cross-department private room -> 403', lambda: query(outsider, status=403))
    check('campus room permits authorized external department', lambda: equal(query(room=fixtures['cross'])['total'], 1))
    check('opening hours normal empty 200', lambda: equal(query(room=fixtures['closed'])['reason'], 'OUTSIDE_OPENING_HOURS'))
    check('non-room resource -> 404', lambda: query(room=fixtures['building'], status=404))
    update_resource(admin, fixtures['building'], active=False)
    check('parent disable is observed without child-version cache', lambda: equal(query()['reason'], 'ROOM_INACTIVE'))
    update_resource(admin, fixtures['building'], active=True)
    check('reenabled parent restores candidates', lambda: equal(query()['total'], 101))
    saved_cookie = student.cookie()
    user = users['student']
    body = dict(displayName=user['displayName'], departmentId=DEPT, roles=['MEMBER'], active=True)
    admin.request('/api/v1/users/' + user['id'], 'PUT', body)
    check('role revocation on same session -> 403', lambda: query(status=403))
    body['roles'] = ['MEMBER', 'SEAT_USER']
    admin.request('/api/v1/users/' + user['id'], 'PUT', body)
    check('role restoration on same session', lambda: equal(query()['total'], 101))
    body['active'] = False
    admin.request('/api/v1/users/' + user['id'], 'PUT', body)
    check('disabled user -> 401', lambda: query(status=401))
    body['active'] = True
    admin.request('/api/v1/users/' + user['id'], 'PUT', body)
    student.login('student', password)
    saved_cookie = student.cookie()
    student.request('/api/v1/auth/logout', 'POST')
    check('logged-out opaque session cannot be replayed', lambda: BrowserHTTP(m_base).request('/api/m1/v1/rooms', cookie=saved_cookie, status=401))
    student.login('student', password)
    for method in ('POST', 'PUT', 'PATCH', 'DELETE'):
        check(method + ' denied by read-only boundary', lambda method=method: student.request('/api/m1/v1/reservations', method, {}, base=m_base, status=405))
    check('all prior HTTP reads leave all three M1 tables unchanged', lambda: equal(m1.command({'op': 'snapshot'}), snapshot))
    fixture = dict(op='create', userId=users['outsider']['id'], roomId=fixtures['room']['id'], seatId=fixtures['seats'][-1]['id'], startAt=start, endAt=end, key='network-fixture-occupied')
    occupied = m1.command(fixture)
    check('existing personal occupancy excludes outlet without writing on read', lambda: equal(query(power=True)['total'], 0))
    check('occupied query keeps complete M1 rows unchanged', lambda: equal(m1.command({'op': 'snapshot'}), occupied))
    fixture.update(userId=identity['id'], roomId=fixtures['cross']['id'], seatId='00000000-0000-0000-0000-000000009999', key='network-fixture-user')
    own = m1.command(fixture)
    check('own overlap in another room blocks candidate list', lambda: equal(query()['reason'], 'USER_TIME_CONFLICT'))
    check('own conflict read preserves M1 rows', lambda: equal(m1.command({'op': 'snapshot'}), own))
    return completed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--demo', action='store_true')
    parser.add_argument('--d-port', type=int, default=18080)
    parser.add_argument('--m1-port', type=int, default=18081)
    parser.add_argument('--web-port', type=int, default=5174)
    parser.add_argument('--browser-check', action='store_true', help='Run optional local Playwright check; supply NODE_PATH and browser runtime')
    args = parser.parse_args()
    dport, mport = (args.d_port, args.m1_port) if args.demo else (free_port(), free_port())
    while not args.demo and dport == mport:
        mport = free_port()
    ports = [dport, mport] + ([args.web_port] if args.demo or args.browser_check else [])
    check_ports(ports)
    password = getpass.getpass('设置本次隔离演示密码（至少12字符，不会显示）：') if args.demo else secrets.token_urlsafe(24)
    if len(password.encode()) < 12 or len(password.encode()) > 72:
        raise RuntimeError('临时密码必须为12至72字节')
    key = secrets.token_urlsafe(40)
    env = dict(os.environ)
    # No inherited Spring/DB/credential overrides from the existing snapshot demo.
    for k in list(env):
        if k.startswith(('SPRING_', 'M1_', 'DATABASE_', 'BOOTSTRAP_', 'COOKIE_', 'LOGGING_')) or k in ('M2_SERVICE_KEY','M3_SERVICE_KEY','PORT','DEBUG','TRACE'):
            del env[k]
    env.update(BOOTSTRAP_ADMIN_PASSWORD=password, M1_SERVICE_KEY=key, M1_D_BASE_URL=f'http://127.0.0.1:{dport}',
               M1_ADVANCE_MODE='ROLLING',M1_MAX_ADVANCE='PT24H', M1_MAX_DURATION='PT4H')
    logdir = ROOT / 'tmp' / ('m1-http-' + str(time.time_ns()))
    logdir.mkdir(parents=True, exist_ok=False)
    with ExitStack() as stack:
        d = launch(stack, 'platform', dport, env, logdir)
        m1 = launch(stack, 'seats', mport, env, logdir)
        d_base, m_base = f'http://127.0.0.1:{dport}', f'http://127.0.0.1:{mport}'
        if args.demo:
            seed(d_base, password, many=False)
        if args.demo or args.browser_check:
            web_env = {k:v for k,v in env.items() if not k.startswith(('BOOTSTRAP_', 'M1_'))}
            web = Process(['npm','run','dev','--','--host','127.0.0.1','--port',str(args.web_port)],
                          dict(web_env, D_PROXY_TARGET=d_base, M1_PROXY_TARGET=m_base), logdir/'web.log', ROOT/'apps/web', fixture=False)
            stack.callback(web.close)
            web_url = f'http://127.0.0.1:{args.web_port}'
            for _ in range(100):
                try:
                    urllib.request.build_opener(urllib.request.ProxyHandler({})).open(web_url, timeout=1).close()
                    break
                except (OSError, urllib.error.URLError):
                    if web.process.poll() is not None:
                        raise RuntimeError('隔离前端启动失败')
                    time.sleep(.1)
            else:
                raise RuntimeError('隔离前端启动超时')
        if args.demo:
            print(f'隔离演示已就绪：{web_url}/seat-query\n账号 student（有M1权限）；账号 manager（仅D空间管理，无M1权限）。使用刚设置的临时密码。\nD/M1各自使用独立临时PG；PT24H/PT4H仅演示参数。Ctrl+C退出本次演示，不影响5173/8080。', flush=True)
            while True:
                if any(p.process.poll() is not None for p in (d,m1,web)):
                    raise RuntimeError('隔离服务异常退出')
                time.sleep(1)
        else:
            if args.browser_check:
                # Browser check starts with clean personal occupancy, then the API suite exercises seeded occupancy.
                seed(d_base, password, many=False)
                browser_env = dict(web_env, HTTP_BROWSER_URL=web_url, HTTP_BROWSER_PASSWORD=password, HTTP_BROWSER_START=period()[0], HTTP_BROWSER_END=period()[1])
                subprocess.run(['node', str(ROOT/'scripts/check_m1_browser.cjs')], env=browser_env, check=True)
                # Fresh API fixture names avoid colliding with the browser fixture: API suite uses another D process.
                d.close();m1.close()
                d = launch(stack,'platform',dport,env,logdir)
                m1 = launch(stack,'seats',mport,env,logdir)
            completed = exercise(d_base, m_base, m1, password)
            # Service credential rejection tested against real D, not just a fake upstream.
            bad_port = free_port()
            bad = launch(stack,'seats',bad_port,env,logdir,M1_SERVICE_KEY=secrets.token_urlsafe(40))
            browser = BrowserHTTP(d_base);browser.login('student',password)
            browser.request('/api/m1/v1/rooms',base=f'http://127.0.0.1:{bad_port}',status=503)
            completed.append('real D rejects incorrect service key -> M1 503')
            print('PASS ' + completed[-1])
            m1.command({'op':'stopDatabase'})
            browser.request('/api/m1/v1/seat-candidates?' + urllib.parse.urlencode(dict(roomId=next(r['id'] for r in browser.request('/api/m1/v1/rooms',base=m_base)[1]['items'] if r['code']=='A101'),startAt=period()[0],endAt=period()[1])),base=m_base,status=503)
            completed.append('M1 database unavailable -> 503')
            print('PASS ' + completed[-1])
            d.close()
            browser.request('/api/m1/v1/rooms',base=m_base,status=503)
            completed.append('real D process unavailable -> M1 503')
            print('PASS ' + completed[-1])
            for log in logdir.glob('*.log'):
                output = log.read_text(encoding='utf-8', errors='replace')
                if password in output or key in output:
                    raise RuntimeError('fixture log unexpectedly contains a credential')
            print(json.dumps({'real_tcp_checks':len(completed),'result':'passed','modules':['D','M1'],'databases':'independent temporary PostgreSQL','browser_checked':args.browser_check}))


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('\n已关闭本次隔离环境。')
