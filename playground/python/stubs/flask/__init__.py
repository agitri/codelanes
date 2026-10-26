"""A stub of the few Flask names the playground uses, so it opens without installing Flask. Not the real thing."""


class Blueprint:
    def __init__(self, name: str, import_name: str, url_prefix: str | None = None):
        self.name = name
        self.url_prefix = url_prefix

    def route(self, rule: str, methods: list | None = None):
        return lambda function: function

    def get(self, rule: str):
        return self.route(rule, ["GET"])

    def post(self, rule: str):
        return self.route(rule, ["POST"])


class Flask(Blueprint):
    def __init__(self, import_name: str):
        super().__init__("app", import_name)

    def register_blueprint(self, blueprint: Blueprint) -> None:
        pass
