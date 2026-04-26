import { request } from '../request';

export type PostFilter = 'all' | 'school' | 'major' | 'mine';

export interface PostAuthor {
  id: number | null;
  username: string;
  avatarUrl: string | null;
  schoolName: string | null;
  schoolDescription: string | null;
  collegeName: string | null;
  majorName: string | null;
}

export interface PostItem {
  id: number;
  title: string;
  content: string;
  likeCount: number;
  liked: boolean;
  createdAt: string;
  updatedAt: string;
  isOwn: boolean;
  author: PostAuthor;
}

export interface PostPage {
  total: number;
  page: number;
  size: number;
  items: PostItem[];
}

export function fetchListPosts(filter: PostFilter, page = 0, size = 20) {
  return request<PostPage>({
    url: '/posts',
    method: 'get',
    params: { filter, page, size }
  });
}

export function fetchCreatePost(title: string, content: string) {
  return request<{ id: number }>({
    url: '/posts',
    method: 'post',
    data: { title, content }
  });
}

export function fetchUpdatePost(id: number, title: string, content: string) {
  return request({
    url: `/posts/${id}`,
    method: 'put',
    data: { title, content }
  });
}

export function fetchDeletePost(id: number) {
  return request({
    url: `/posts/${id}`,
    method: 'delete'
  });
}

export function fetchTogglePostLike(id: number) {
  return request<{ liked: boolean; likeCount: number }>({
    url: `/posts/${id}/like`,
    method: 'post'
  });
}

export function fetchGetMajors() {
  return request<{ tagId: string; name: string; description: string | null }[]>({
    url: '/users/majors'
  });
}

export function fetchUpdateMajor(majorTag: string | null) {
  return request({
    url: '/users/major',
    method: 'put',
    data: { majorTag: majorTag ?? '' }
  });
}
