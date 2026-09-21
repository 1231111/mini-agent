-- 意图分类层已删除，这 6 张表随之成为孤儿表。
--
-- 它们由已下线的规则集链路建出（规则集 / 规则 / 工具画像 / 命中日志 / 反馈 / 提案）。
-- 全量检索 Java、XML、YAML 与配置后确认：除本目录 V4 的建表语句外，没有任何读写方，
-- 线上留着只是占位与误导 —— 后来人会以为「意图规则」还是活的功能。
--
-- 两点纪律：
--   1) V4 不动。已执行过的迁移改一个字都会 checksum 不匹配，应用起不来。
--   2) 按引用倒序删。子表都带 rule_set_id 指向 intent_rule_set，先删子表再删父表。

DROP TABLE IF EXISTS intent_rule_proposal;
DROP TABLE IF EXISTS intent_rule_feedback;
DROP TABLE IF EXISTS intent_rule_hit_log;
DROP TABLE IF EXISTS intent_tool_profile;
DROP TABLE IF EXISTS intent_rule;
DROP TABLE IF EXISTS intent_rule_set;
