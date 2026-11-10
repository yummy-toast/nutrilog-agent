"""真实HTTP验证本地演示登录与会话归属，不调用模型，不打印口令/token/cookie。"""
from pathlib import Path
from http.cookiejar import CookieJar
import argparse
import json
import subprocess
import time
import urllib.request
import urllib.parse
import urllib.error

parser=argparse.ArgumentParser()
parser.add_argument('--no-demo',action='store_true')
args=parser.parse_args()
root=Path(__file__).resolve().parent.parent
base='http://127.0.0.1:18083/api/v1'
class Browser:
    def __init__(self):
        self.cookies=CookieJar()
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
    def call(self,path,method='GET',body=None,csrf=None,form=None):
        headers={}
        data=None
        if body is not None:
            data=json.dumps(body).encode(); headers['Content-Type']='application/json'
        if form is not None:
            data=urllib.parse.urlencode(form).encode(); headers['Content-Type']='application/x-www-form-urlencoded'
        if csrf: headers[csrf['headerName']]=csrf['token']
        req=urllib.request.Request(base+path,data=data,headers=headers,method=method)
        try: response=self.opener.open(req,timeout=3)
        except urllib.error.HTTPError as error: response=error
        with response:
            text=response.read()
            return response.status,json.loads(text) if text else None
    def csrf(self):
        status,token=self.call('/auth/csrf'); assert status==200; return token
    def login(self,username,password):
        token=self.csrf()
        before=[cookie.value for cookie in self.cookies if cookie.name=='JSESSIONID']
        assert self.call('/auth/login','POST',csrf=token,form={'username':username,'password':password})[0]==200
        after=[cookie.value for cookie in self.cookies if cookie.name=='JSESSIONID']
        assert before and after and before!=after, '登录后应轮换会话ID'

with (root/'.tools/session-auth.log').open('w') as log:
    process=subprocess.Popen([str(root/'.tools/jdk/Contents/Home/bin/java'),'-jar',
        str(root/'backend/target/nutrilog-agent-1.0.0.jar'),
        *([] if args.no_demo else ['--spring.profiles.active=demo-auth']),'--server.port=18083'],stdout=log,stderr=subprocess.STDOUT)
    try:
        anon=Browser()
        for _ in range(80):
            if process.poll() is not None: raise RuntimeError('应用启动失败，请检查.tools/session-auth.log')
            try:
                if anon.call('/health')[0]==200: break
            except OSError: pass
            time.sleep(.5)
        else: raise RuntimeError('启动超时')
        assert anon.call('/sessions')[0]==401
        assert anon.call('/auth/me')[0]==401
        assert anon.call('/auth/login','POST',form={'username':'alice','password':'alice-demo-only'})[0]==403
        token=anon.csrf()
        assert anon.call('/auth/login','POST',csrf=token,form={'username':'alice','password':'wrong'})[0]==401
        if args.no_demo:
            assert anon.call('/auth/login','POST',csrf=anon.csrf(),form={'username':'alice','password':'alice-demo-only'})[0]==401
            print('默认配置HTTP通过：演示账号不可登录，受保护接口拒绝匿名请求。')
            raise SystemExit(0)
        alice=Browser(); bob=Browser()
        alice.login('alice','alice-demo-only'); bob.login('bob','bob-demo-only')
        assert alice.call('/auth/me')[1]['username']=='alice'
        assert bob.call('/auth/me?userId=alice')[1]['username']=='bob'
        assert alice.call('/sessions','POST',body={'title':'早餐'})[0]==403
        token=alice.csrf()
        status,session=alice.call('/sessions','POST',body={'title':'早餐','userId':'bob'},csrf=token)
        assert status==200
        sid=session['sessionId']
        status,second=alice.call('/sessions','POST',body={'title':'晚餐'},csrf=token)
        assert status==200 and second['sessionId']!=sid
        assert len(alice.call('/sessions')[1])==2
        assert bob.call('/sessions')[1]==[]
        assert alice.call('/sessions/'+sid)[0]==200
        denied=bob.call('/sessions/'+sid)
        assert denied==(403,{'code':'SESSION_ACCESS_DENIED'})
        assert bob.call('/sessions/'+sid,'DELETE',csrf=bob.csrf())[0]==403
        assert alice.call('/sessions/'+sid)[0]==200
        assert alice.call('/sessions/'+sid,'DELETE')[0]==403
        assert alice.call('/sessions/'+sid,'DELETE',csrf=token)[0]==204
        assert alice.call('/sessions/'+sid)[0]==403
        assert alice.call('/auth/logout','POST',csrf=alice.csrf())[0]==200
        assert alice.call('/auth/me')[0]==401
        assert alice.call('/sessions')[0]==401
        assert bob.call('/auth/me')[0]==200
        print('HTTP通过：登录/会话ID轮换/CSRF/身份防伪造/两用户隔离/两会话隔离/删除权限/退出失效。')
    finally:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill();process.wait()
