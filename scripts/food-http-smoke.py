"""运行已打包的Jar，验证食品HTTP契约；结束后停止应用。"""
from pathlib import Path
import json
import subprocess
import time
import urllib.request
import urllib.error

root = Path(__file__).resolve().parent.parent
base = 'http://127.0.0.1:18081/api/v1'
def request(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=3) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, None

with (root / '.tools/food-http.log').open('w') as log:
    process = subprocess.Popen([str(root / '.tools/jdk/Contents/Home/bin/java'), '-jar',
        str(root / 'backend/target/nutrilog-agent-0.0.1-SNAPSHOT.jar'), '--server.port=18081'],
        stdout=log, stderr=subprocess.STDOUT)
    try:
        for _ in range(60):
            if process.poll() is not None:
                raise RuntimeError('应用退出，请检查.tools/food-http.log')
            try:
                if request('/health')[0] == 200:
                    break
            except OSError:
                pass
            time.sleep(.5)
        else:
            raise RuntimeError('启动超时')
        query = urllib.parse.urlencode({'keyword': '米饭'})
        status, foods = request('/foods?' + query)
        assert status == 200 and len(foods) == 2
        assert {food['state'] for food in foods} == {'RAW', 'COOKED'}
        status, result = request('/nutrition/calculate', {'foodId': 168935, 'weightGrams': 200})
        assert status == 200 and result['energy']['amount'] == 260 and not result['source']['demo']
        assert result['source']['externalId'] == 168935
        assert 'with salt' in result['source']['originalDescription']
        assert len(result['source']['archiveSha256']) == 64
        assert request('/nutrition/calculate', {'foodId': 168935, 'weightGrams': 0})[0] == 400
        assert request('/nutrition/calculate', {'foodId': 999, 'weightGrams': 200})[0] == 404
        assert request('/nutrition/calculate', {})[0] == 400
        print('食品HTTP检查通过：候选歧义、真实条目计算、来源与原始描述、400/404。')
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
