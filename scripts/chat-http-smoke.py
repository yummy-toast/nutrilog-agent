"""打包后运行；--live会产生API费用并要求进程已有DEEPSEEK_API_KEY。"""
from pathlib import Path
import argparse
import json
import os
import subprocess
import time
import urllib.request
import urllib.error

parser = argparse.ArgumentParser()
parser.add_argument('--live', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
if args.live and not os.environ.get('DEEPSEEK_API_KEY'):
    raise SystemExit('请先在本地配置DEEPSEEK_API_KEY')
command = [str(root/'.tools/jdk/Contents/Home/bin/java'), '-Dstdout.encoding=UTF-8',
           '-Dstderr.encoding=UTF-8', '-jar', str(root/'backend/target/nutrilog-agent-0.0.1-SNAPSHOT.jar'),
           '--server.port=18082']
if args.live:
    command.append('--spring.profiles.active=live')
def call(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request('http://127.0.0.1:18082/api/v1'+path, data=data,
        headers={'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=100) as response:
            body = json.load(response)
            if path == '/chat': assert body['requestId'] == response.headers.get('X-Request-Id')
            return response.status, body
    except urllib.error.HTTPError as response:
        body = json.load(response)
        if path == '/chat': assert body['requestId'] == response.headers.get('X-Request-Id')
        return response.code, body
with (root/'.tools/chat-http.log').open('w') as log:
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
    try:
        for _ in range(60):
            if process.poll() is not None: raise RuntimeError('应用启动失败')
            try:
                if call('/health')[0] == 200: break
            except OSError: pass
            time.sleep(.5)
        else: raise RuntimeError('应用启动超时')
        assert call('/chat', {'message':' '})[0] == 400
        if args.live:
            status, result = call('/chat', {'message':'用户选定食品168935（长粒白米饭，熟，未强化，加盐），请查询200克的营养。'})
            assert status == 200 and result['code'] == 'OK', (status, result)
            assert '260' in result['answer']
            print('COMPLETE', json.dumps(result, ensure_ascii=False))
            status, result = call('/chat', {'message':'200克呢？'})
            assert status == 200
            print('NO_HISTORY', json.dumps(result, ensure_ascii=False))
        else:
            status, result = call('/chat', {'message':'查询米饭'})
            assert status == 503 and result['code'] == 'MODEL_UNAVAILABLE'
        print('CHAT_HTTP通过，单轮语义仍须人工复核')
    finally:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill();process.wait()
