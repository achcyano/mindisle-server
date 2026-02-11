# Mindisle 用户认证与资料接口规范

## 1. 基本约定
- Base URL: `/api/v1`
- Content-Type: `application/json`
- 编码: `UTF-8`
- 鉴权头: `Authorization: Bearer <accessToken>`
- 设备头: `X-Device-Id: <device-id>`（登录相关接口必传）

### 1.1 统一响应体
所有接口统一返回：

```json
{
  "code": 0,
  "message": "OK",
  "data": {}
}
```

字段说明：
- `code`: 业务码，`0` 表示成功。
- `message`: 文本消息。
- `data`: 业务数据对象；无业务数据时可能为 `null`。

### 1.2 常用错误码
- `40000`: 请求体非法或缺少必要参数。
- `40001`: 手机号格式非法。
- `40002`: 密码长度不足（< 6）。
- `40003`: 短信验证码错误或已过期。
- `40004`: 直登票据无效或过期。
- `40100`: 未认证或 token 无效。
- `40101`: 密码错误。
- `40401`: 该手机号未注册（登录场景）。
- `40402`: 该手机号未注册（重置密码发码/重置）。
- `40901`: 该手机号已注册（注册发码/注册）。
- `42901`: 短信请求过于频繁（60 秒限制）。
- `42902`: 当日短信请求次数超过上限（默认 10 次）。

### 1.3 手机号格式
当前仅支持中国大陆手机号（`1[3-9]` 开头共 11 位）。

- 推荐客户端直接传 11 位手机号，例如：`13812345678`。
- 若客户端传入 `+86` 前缀（如 `+8613812345678`）或 `86` 前缀（如 `8613812345678`），服务端会自动剥离前缀再校验。
- 服务端持久化及响应中的 `phone` 字段统一为不带国家码的 11 位手机号。

## 2. 枚举定义

### 2.1 短信用途 `SmsPurpose`
- `REGISTER`
- `RESET_PASSWORD`

### 2.2 登录预检结果 `LoginDecision`
- `REGISTER_REQUIRED`: 手机号未注册，客户端应引导注册。
- `DIRECT_LOGIN_ALLOWED`: 手机号已注册且该设备历史登录过，可走直登。
- `PASSWORD_REQUIRED`: 手机号已注册但为新设备，需密码登录。

### 2.3 性别 `Gender`
- `UNKNOWN`
- `MALE`
- `FEMALE`
- `OTHER`

## 3. 接口清单

## 3.1 发送短信验证码
`POST /auth/sms-codes`

请求体：
```json
{
  "phone": "13812345678",
  "purpose": "REGISTER"
}
```

成功响应（202）：
```json
{
  "code": 0,
  "message": "Accepted",
  "data": null
}
```

说明：
- 同一个手机号 60 秒内只能获取一次验证码。
- 每手机号每日最多请求 10 次验证码。
- 短信发送通道目前是 TODO（服务端已预留）。

## 3.2 注册
`POST /auth/register`

请求头：
- `X-Device-Id` 必传。

请求体（必须包含 `smsCode` 和 `password`）：
```json
{
  "phone": "13812345678",
  "smsCode": "123456",
  "password": "abc12345",
  "profile": {
    "fullName": "张三",
    "gender": "MALE",
    "birthDate": "1998-05-10",
    "weightKg": 63.5,
    "familyHistory": ["抑郁症家族史"],
    "medicalHistory": ["焦虑障碍"],
    "medicationHistory": ["舍曲林 50mg qd"]
  }
}
```

成功响应（201）：
```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "userId": 1001,
    "token": {
      "accessToken": "<jwt>",
      "refreshToken": "<refresh-token>",
      "accessTokenExpiresInSeconds": 1800,
      "refreshTokenExpiresInSeconds": 15552000
    }
  }
}
```

## 3.3 登录预检
`POST /auth/login/check`

请求头：
- `X-Device-Id` 必传。

请求体：
```json
{
  "phone": "13812345678"
}
```

成功响应（`REGISTER_REQUIRED`）：
```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "decision": "REGISTER_REQUIRED",
    "ticket": null
  }
}
```

成功响应（`DIRECT_LOGIN_ALLOWED`）：
```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "decision": "DIRECT_LOGIN_ALLOWED",
    "ticket": "<one-time-ticket>"
  }
}
```

成功响应（`PASSWORD_REQUIRED`）：
```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "decision": "PASSWORD_REQUIRED",
    "ticket": null
  }
}
```

## 3.4 直登（老设备）
`POST /auth/login/direct`

请求头：
- `X-Device-Id` 必传。

请求体：
```json
{
  "phone": "13812345678",
  "ticket": "<one-time-ticket>"
}
```

成功响应（200）：
```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "userId": 1001,
    "token": {
      "accessToken": "<jwt>",
      "refreshToken": "<refresh-token>",
      "accessTokenExpiresInSeconds": 1800,
      "refreshTokenExpiresInSeconds": 15552000
    }
  }
}
```

## 3.5 密码登录（新设备）
`POST /auth/login/password`

请求头：
- `X-Device-Id` 必传。

请求体：
```json
{
  "phone": "13812345678",
  "password": "abc12345"
}
```

成功响应：同 `3.4`。

## 3.6 刷新令牌
`POST /auth/token/refresh`

请求头：
- `X-Device-Id` 必传。

请求体：
```json
{
  "refreshToken": "<refresh-token>"
}
```

成功响应：同 `3.4`。

## 3.7 重置密码
`POST /auth/password/reset`

请求体：
```json
{
  "phone": "13812345678",
  "smsCode": "123456",
  "newPassword": "newPwd123"
}
```

成功响应：
```json
{
  "code": 0,
  "message": "Password reset success",
  "data": null
}
```

说明：
- 重置成功后，服务端会撤销该用户全部设备会话，客户端需重新登录。

## 3.8 登出
`POST /auth/logout`

请求头：
- `Authorization` 必传。
- `X-Device-Id` 必传。

请求体（可选，不传也可）：
```json
{
  "refreshToken": "<refresh-token>"
}
```

成功响应：
```json
{
  "code": 0,
  "message": "Logged out",
  "data": null
}
```

## 3.9 获取当前用户资料
`GET /users/me`

请求头：
- `Authorization` 必传。

成功响应：
```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "userId": 1001,
    "phone": "13812345678",
    "fullName": "张三",
    "gender": "MALE",
    "birthDate": "1998-05-10",
    "weightKg": 63.5,
    "familyHistory": ["抑郁症家族史"],
    "medicalHistory": ["焦虑障碍"],
    "medicationHistory": ["舍曲林 50mg qd"]
  }
}
```

## 3.10 更新当前用户资料
`PUT /users/me/profile`

请求头：
- `Authorization` 必传。

请求体（按需部分更新）：
```json
{
  "fullName": "李四",
  "gender": "FEMALE",
  "birthDate": "1996-01-15",
  "weightKg": 52.0,
  "familyHistory": ["双相障碍家族史"],
  "medicalHistory": ["失眠症"],
  "medicationHistory": ["艾司唑仑 1mg qn"]
}
```

成功响应：与 `3.9` 格式一致，返回更新后资料。

## 4. 失败响应示例

请求体非法：
```json
{
  "code": 40000,
  "message": "Invalid request body",
  "data": null
}
```

缺少设备头：
```json
{
  "code": 40000,
  "message": "Missing required header: X-Device-Id",
  "data": null
}
```

验证码错误：
```json
{
  "code": 40003,
  "message": "Invalid or expired sms code",
  "data": null
}
```

## 5. 客户端流程建议

1. 登录页先调用 `POST /auth/login/check`。
2. 根据 `decision` 分流：
- `REGISTER_REQUIRED` -> 注册流程。
- `DIRECT_LOGIN_ALLOWED` -> 调 `POST /auth/login/direct`。
- `PASSWORD_REQUIRED` -> 调 `POST /auth/login/password`。
3. 注册流程：先发码，再 `POST /auth/register`（必须带 `smsCode + password`）。
4. token 过期后调用 `POST /auth/token/refresh`。
5. 忘记密码走 `POST /auth/password/reset`。


