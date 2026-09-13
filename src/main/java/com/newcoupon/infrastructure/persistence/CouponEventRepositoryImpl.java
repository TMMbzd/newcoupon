package com.newcoupon.infrastructure.persistence;

import com.newcoupon.domain.coupon.CouponEvent;
import com.newcoupon.domain.repository.CouponEventRepository;
import com.newcoupon.infrastructure.persistence.converter.CouponEventPOConverter;
import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.persistence.mapper.CouponEventMapper;
import com.newcoupon.infrastructure.persistence.po.CouponEventPO;
import org.springframework.stereotype.Repository;

/** 券事件仓储适配器（append-only） */
@Repository
public class CouponEventRepositoryImpl implements CouponEventRepository {

    private final CouponEventMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    public CouponEventRepositoryImpl(CouponEventMapper mapper, SnowflakeIdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public void append(CouponEvent event) {
        CouponEventPO po = CouponEventPOConverter.toPO(event);
        po.setId(idGenerator.next());   // 雪花ID（批次二）
        mapper.insert(po);
    }
}
