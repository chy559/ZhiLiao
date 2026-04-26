import { request } from '../request';

/**
 * Login
 *
 * @param username User name
 * @param password Password
 */
export function fetchLogin(username: string, password: string) {
  return request<Api.Auth.LoginToken>({
    url: '/users/login',
    method: 'post',
    data: {
      username,
      password
    }
  });
}

export function fetchLogout() {
  return request({ url: '/users/logout', method: 'post' });
}

export function fetchRegister(username: string, password: string, email: string, emailCode: string) {
  return request({
    url: '/users/register',
    method: 'post',
    data: {
      username,
      password,
      email,
      emailCode
    }
  });
}

/** 发送邮箱验证码。scene: register | reset | bind */
export function fetchSendEmailCode(email: string, scene: 'register' | 'reset' | 'bind') {
  return request({
    url: '/users/send-email-code',
    method: 'post',
    data: { email, scene }
  });
}

/** 忘记密码 - 通过邮箱验证码重置 */
export function fetchResetPassword(email: string, code: string, newPassword: string) {
  return request({
    url: '/users/reset-password',
    method: 'post',
    data: { email, code, newPassword }
  });
}

/** 已登录用户绑定邮箱 */
export function fetchBindEmail(email: string, code: string) {
  return request({
    url: '/users/bind-email',
    method: 'post',
    data: { email, code }
  });
}

/** Get user info */
export function fetchGetUserInfo() {
  return request<Api.Auth.UserInfo>({ url: '/users/me' });
}

/** Update username (also renames the user's private org tag) */
export function fetchUpdateUsername(newUsername: string) {
  return request<{ username: string; token: string; refreshToken: string }>({
    url: '/users/profile',
    method: 'put',
    data: { newUsername }
  });
}

/** Update password */
export function fetchUpdatePassword(oldPassword: string, newPassword: string) {
  return request({
    url: '/users/password',
    method: 'put',
    data: { oldPassword, newPassword }
  });
}

/** 学校 + 学院级联列表（登录用户） */
export function fetchSchoolsAndColleges() {
  return request<{ tagId: string; name: string; colleges: { tagId: string; name: string }[] }[]>({
    url: '/users/schools-and-colleges'
  });
}

/** 设置当前用户的学校 / 学院；传空字符串表示清除 */
export function fetchUpdateSchoolCollege(schoolTag: string | null, collegeTag: string | null) {
  return request({
    url: '/users/school-college',
    method: 'put',
    data: { schoolTag: schoolTag ?? '', collegeTag: collegeTag ?? '' }
  });
}

/** 管理员批量导入学校/学院（CSV 全文） */
export function fetchBulkImportOrgTags(csv: string) {
  return request<{ inserted: number; updated: number; failed: number }>({
    url: '/admin/org-tags/bulk-import',
    method: 'post',
    headers: { 'Content-Type': 'text/plain' },
    data: csv
  });
}

/** 注销账号：彻底删除当前账号和关联资源（管理员会被服务端拒绝） */
export function fetchDeleteAccount() {
  return request({
    url: '/users/me',
    method: 'delete'
  });
}

/** Upload user avatar */
export function fetchUploadAvatar(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  // 不要手动设 Content-Type：axios 会自动加上正确的 multipart boundary
  return request<{ avatarUrl: string }>({
    url: '/users/avatar',
    method: 'post',
    data: formData
  });
}

/**
 * Refresh token
 *
 * @param refreshToken Refresh token
 */
export function fetchRefreshToken(refreshToken: string) {
  return request<Api.Auth.LoginToken>({
    url: '/auth/refreshToken',
    method: 'post',
    data: {
      refreshToken
    }
  });
}

/**
 * return custom backend error
 *
 * @param code error code
 * @param msg error message
 */
export function fetchCustomBackendError(code: string, msg: string) {
  return request({ url: '/auth/error', params: { code, msg } });
}
