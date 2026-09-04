package com.newcoupon.infrastructure.persistence;

import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.persistence.converter.TemplatePOConverter;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 模板仓储适配器：实现领域端口，把 MyBatis-Plus 细节关在本类内。
 * 领域层与其他层永远不知道 PO/Mapper 的存在。
 */
@Repository
public class TemplateRepositoryImpl implements TemplateRepository {

    private final TemplateMapper mapper;

    public TemplateRepositoryImpl(TemplateMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public CouponTemplate save(CouponTemplate template) {
        CouponTemplatePO po = TemplatePOConverter.toPO(template);
        // insert 后自增主键由 MyBatis-Plus 回填进 po.getId()
        mapper.insert(po);
        return template.withId(po.getId());
    }

    @Override
    public Optional<CouponTemplate> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id))
                .map(TemplatePOConverter::toDomain);
    }

    @Override
    public boolean casUpdatePublishStatus(long id, PublishStatus from, PublishStatus to) {
        return mapper.casUpdatePublishStatus(id, from.getCode(), to.getCode()) == 1;
    }
}
