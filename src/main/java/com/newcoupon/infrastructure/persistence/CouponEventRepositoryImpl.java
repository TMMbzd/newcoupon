package com.newcoupon.infrastructure.persistence;

import com.newcoupon.domain.coupon.CouponEvent;
import com.newcoupon.domain.repository.CouponEventRepository;
import com.newcoupon.infrastructure.persistence.converter.CouponEventPOConverter;
import com.newcoupon.infrastructure.persistence.mapper.CouponEventMapper;
import org.springframework.stereotype.Repository;

/** 券事件仓储适配器（append-only） */
@Repository
public class CouponEventRepositoryImpl implements CouponEventRepository {

    private final CouponEventMapper mapper;

    public CouponEventRepositoryImpl(CouponEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(CouponEvent event) {
        mapper.insert(CouponEventPOConverter.toPO(event));
    }
}
