from django.urls import path

from . import views

urlpatterns = [
    path("", views.post_list, name="post-list"),
    path("posts/<int:pk>/", views.PostDetail.as_view(), name="post-detail"),
]
