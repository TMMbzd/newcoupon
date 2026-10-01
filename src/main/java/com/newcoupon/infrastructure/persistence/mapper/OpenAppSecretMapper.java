package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppSecretPO;
import org.apache.ibatis.annotations.Mapper;

/** 接入方密钥 Mapper（S1） */
@Mapper
public interface OpenAppSecretMapper extends BaseMapper<OpenAppSecretPO> {
}
