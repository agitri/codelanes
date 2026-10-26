class HttpResponse:
    def __init__(self, content: str = "", status: int = 200):
        self.content = content
        self.status = status
