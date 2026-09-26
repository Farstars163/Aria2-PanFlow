package com.farstars.domain;

import lombok.Data;

@Data
public class Xunlei {
    protected String action;
    /** 迅雷云盘文件 id */
    protected String fileId;
    protected String filename;
    /** 迅雷云盘访问令牌(从 pan.xunlei.com localStorage credentials_* 获取) */
    protected String accessToken;
    /** 令牌类型, 默认 Bearer */
    protected String tokenType;
    /** 验证令牌(可选, 对应 localStorage captcha_* 的 token) */
    protected String captchaToken;
    /** 设备 ID(可选, 对应 localStorage deviceid 的 32 位十六进制) */
    protected String deviceId;
    protected String dir;
}
