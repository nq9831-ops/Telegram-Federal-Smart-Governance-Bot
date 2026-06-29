-- V2: 删除从未被业务代码使用的孤儿表
-- rating_rules 和 version_log 表有 Entity 和 Service 定义，但代码中无任何调用点，属于死代码
DROP TABLE IF EXISTS rating_rules;
DROP TABLE IF EXISTS version_log;
