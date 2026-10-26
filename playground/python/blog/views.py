from django.http import HttpResponse
from django.views import View

POSTS = {1: "Hello, CodeLanes"}


def post_list(request) -> HttpResponse:
    return HttpResponse("\n".join(POSTS.values()))


class PostDetail(View):
    def get(self, request, pk: int) -> HttpResponse:
        return HttpResponse(self._title(pk))

    def post(self, request, pk: int) -> HttpResponse:
        POSTS[pk] = request.POST["title"]
        return self.get(request, pk)

    def _title(self, pk: int) -> str:
        return POSTS.get(pk, "No such post")
