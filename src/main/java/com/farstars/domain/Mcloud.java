package com.farstars.domain;

import lombok.Data;

@Data
public class Mcloud {
    protected String action;
    /** 移动云盘 Authorization 头值(来自 cookie 中的 authorization) */
    protected String authorization;
    protected String fileId;
    protected String filename;
    protected String dir;
}
