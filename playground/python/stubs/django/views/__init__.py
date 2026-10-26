class View:
    @classmethod
    def as_view(cls):
        return lambda request, *args, **kwargs: cls().dispatch(request, *args, **kwargs)

    def dispatch(self, request, *args, **kwargs):
        return getattr(self, request.method.lower())(request, *args, **kwargs)
