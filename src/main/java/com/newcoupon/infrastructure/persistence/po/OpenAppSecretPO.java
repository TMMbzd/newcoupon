package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 接入方密钥 PO，对应 open_app_secret（V14，S1）。secret_cipher 是 AES-GCM 密文，永不存明文 */
@TableName("open_app_secret")
public class OpenAppSecretPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appId;
    private String secretCipher;
    private Integer status;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getSecretCipher() { return secretCipher; }
    public void setSecretCipher(String secretCipher) { this.secretCipher = secretCipher; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(LocalDateTime expireTime) { this.expireTime = expireTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
