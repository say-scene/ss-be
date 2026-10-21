"""로컬 HTTP 연결 검증용. 실제 모델 추론을 수행하지 않는다."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != '/v1/chat/completions':
            self.send_error(404)
            return
        try:
            request = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
            if request.get('model') != 'mock-cafe' or request.get('stream') is not False:
                self.send_error(400)
                return
        except (ValueError, TypeError):
            self.send_error(400)
            return
        data = json.dumps({'choices': [{'message': {'role': 'assistant', 'content': 'Hi! Welcome to SayScene Cafe. What can I get for you today?'}}]}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def log_message(self, *args):
        pass

if __name__ == '__main__':
    print('로컬 모의 추론 서버: http://127.0.0.1:8000', flush=True)
    ThreadingHTTPServer(('127.0.0.1', 8000), Handler).serve_forever()
