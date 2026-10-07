package com.cloudai.service;

import com.cloudai.common.BizException;
import com.cloudai.common.Crypto;
import com.cloudai.common.Json;
import com.cloudai.entity.PluginEntities.Credential;
import com.cloudai.mapper.Mappers.CredentialMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 凭据加解密。凭据字段值只在服务端解密，发往执行器作为环境变量，前端永不回显原值。 */
@Service
@RequiredArgsConstructor
public class CredentialService {
    private final CredentialMapper mapper;
    private final Crypto crypto;

    public record Field(String name, String envName, String value) {}

    public Credential get(String spaceId, Long id) {
        Credential c = mapper.selectById(id);
        if (c == null || !spaceId.equals(c.getSpaceId())) throw BizException.notFound("凭据");
        return c;
    }

    public List<Field> fields(Credential c) {
        JsonNode arr = Json.read(crypto.decrypt(c.getFieldsEnc()));
        List<Field> out = new java.util.ArrayList<>();
        if (arr != null) for (JsonNode f : arr) {
            out.add(new Field(f.path("name").asText(), f.path("envName").asText(f.path("name").asText()), f.path("value").asText()));
        }
        return out;
    }

    /** name -> value */
    public Map<String, String> values(String spaceId, Long id) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Field f : fields(get(spaceId, id))) m.put(f.name(), f.value());
        return m;
    }

    /** 保存字段：value 为空的字段保留旧值（编辑时前端不回显原值） */
    public void setFields(Credential c, List<Field> incoming) {
        Map<String, String> old = new LinkedHashMap<>();
        if (c.getFieldsEnc() != null) for (Field f : fields(c)) old.put(f.name(), f.value());
        ArrayNode enc = Json.arr();
        ArrayNode names = Json.arr();
        for (Field f : incoming) {
            if (f.name() == null || f.name().isBlank()) continue;
            String v = (f.value() == null || f.value().isEmpty()) ? old.get(f.name()) : f.value();
            BizException.check(v != null, "字段 " + f.name() + " 缺少密钥值");
            String env = f.envName() == null || f.envName().isBlank() ? f.name().toUpperCase() : f.envName();
            enc.add(Json.obj().put("name", f.name()).put("envName", env).put("value", v));
            names.add(Json.obj().put("name", f.name()).put("envName", env));
        }
        c.setFieldsEnc(crypto.encrypt(Json.write(enc)));
        c.setFieldNames(names);
    }
}
