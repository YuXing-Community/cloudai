package com.cloudai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.common.BizException;
import com.cloudai.common.Crypto;
import com.cloudai.common.Json;
import com.cloudai.entity.ModelEntities.ModelDef;
import com.cloudai.entity.ModelEntities.ModelProvider;
import com.cloudai.mapper.Mappers.ModelDefMapper;
import com.cloudai.mapper.Mappers.ModelProviderMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 模型引用解析：providerKey/modelId -> 调用参数；费用计算 */
@Service
@RequiredArgsConstructor
public class ModelService {
    private final ModelProviderMapper providerMapper;
    private final ModelDefMapper defMapper;
    private final Crypto crypto;

    public record Resolved(ModelProvider provider, ModelDef model) {}

    public Resolved resolve(String spaceId, String ref) {
        if (ref == null || !ref.contains("/")) throw new BizException("未配置模型，请在 Agent 编排配置中选择模型");
        int i = ref.indexOf('/');
        String pk = ref.substring(0, i), mid = ref.substring(i + 1);
        ModelProvider p = providerMapper.selectOne(new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getSpaceId, spaceId).eq(ModelProvider::getProviderKey, pk));
        if (p == null) throw new BizException("模型 Provider 不存在: " + pk);
        ModelDef d = defMapper.selectOne(new LambdaQueryWrapper<ModelDef>()
                .eq(ModelDef::getProviderId, p.getId()).eq(ModelDef::getModelId, mid).last("limit 1"));
        if (d == null) throw new BizException("模型不存在: " + ref);
        return new Resolved(p, d);
    }

    /** 当前空间默认模型（is_default 优先，否则第一个） */
    public String defaultRef(String spaceId) {
        var providers = providerMapper.selectList(new LambdaQueryWrapper<ModelProvider>().eq(ModelProvider::getSpaceId, spaceId));
        String first = null;
        for (ModelProvider p : providers) {
            for (ModelDef d : defMapper.selectList(new LambdaQueryWrapper<ModelDef>().eq(ModelDef::getProviderId, p.getId()))) {
                String ref = p.getProviderKey() + "/" + d.getModelId();
                if (Boolean.TRUE.equals(d.getIsDefault())) return ref;
                if (first == null) first = ref;
            }
        }
        return first;
    }

    /** 给执行器的模型调用参数（含解密后的 key） */
    public ObjectNode toExecutorSpec(Resolved r) {
        ObjectNode n = Json.obj();
        n.put("ref", r.provider().getProviderKey() + "/" + r.model().getModelId());
        n.put("provider", r.provider().getProviderKey());
        n.put("model", r.model().getModelId());
        n.put("base_url", r.provider().getBaseUrl());
        n.put("api_key", crypto.decrypt(r.provider().getApiKeyEnc()));
        n.put("api_type", r.provider().getApiType());
        n.put("context_window", r.model().getContextWindow());
        n.put("max_tokens", r.model().getMaxTokens());
        if (r.model().getExtra() != null) n.set("extra", r.model().getExtra());
        return n;
    }

    /** 元/百万 token；任一价格缺失返回 null（前端显示“已跳过”） */
    public BigDecimal cost(Resolved r, long in, long out, long cacheRead) {
        ModelDef d = r.model();
        if (d.getInputPrice() == null || d.getOutputPrice() == null) return null;
        BigDecimal m = BigDecimal.valueOf(1_000_000);
        BigDecimal cachePrice = d.getCachePrice() == null ? d.getInputPrice() : d.getCachePrice();
        long freshIn = Math.max(0, in - cacheRead);
        return d.getInputPrice().multiply(BigDecimal.valueOf(freshIn))
                .add(cachePrice.multiply(BigDecimal.valueOf(cacheRead)))
                .add(d.getOutputPrice().multiply(BigDecimal.valueOf(out)))
                .divide(m, 6, RoundingMode.HALF_UP);
    }
}
