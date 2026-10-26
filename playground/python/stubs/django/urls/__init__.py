def path(route: str, view, kwargs=None, name: str | None = None):
    return route, view


def re_path(route: str, view, kwargs=None, name: str | None = None):
    return route, view


def include(module):
    return module
