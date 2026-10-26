"""A stub of the few FastAPI names the playground uses, so it opens without installing FastAPI. Not the real thing."""


class APIRouter:
    def __init__(self, prefix: str = "", tags: list | None = None):
        self.prefix = prefix

    def api_route(self, path: str, methods: list | None = None):
        return lambda function: function

    def get(self, path: str):
        return self.api_route(path, ["GET"])

    def post(self, path: str):
        return self.api_route(path, ["POST"])

    def include_router(self, router: "APIRouter") -> None:
        pass


class FastAPI(APIRouter):
    pass
