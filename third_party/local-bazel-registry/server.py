import http.server
import sys
import threading
import time

DIRECTORY = "./third_party/local-bazel-registry"

class Handler(http.server.SimpleHTTPRequestHandler):
  def __init__(self, *args, **kwargs):
    super().__init__(*args, directory=DIRECTORY, **kwargs)

def _serve(httpd):
  httpd.serve_forever()

print("creating server...")
httpd = http.server.HTTPServer(('localhost', 8000), Handler)
t = threading.Thread(target=_serve, name="httpserver", args=(httpd,))
print("starting server")
t.start()
print("server started, waiting for 30 mins")
t.join(1800)
print("shutting down...")
httpd.shutdown()