-- =====================================================================
-- V3  时态关联语义：机构维表版本、关联语义目录、有效期匹配、
--     连接基数模型、改写沙箱、重复行归因（本期新增元数据模型）
-- =====================================================================

-- 机构维表版本（SCD2，闭开区间 [valid_from, valid_to)；valid_to NULL = 当前有效）
CREATE TABLE dim_org_version (
    id            BIGSERIAL PRIMARY KEY,
    org_code      TEXT NOT NULL,
    version_no    INT  NOT NULL,
    org_name      TEXT NOT NULL,
    region        TEXT NOT NULL,
    org_level     TEXT NOT NULL,
    valid_from    DATE NOT NULL,
    valid_to      DATE,
    revision_note TEXT,
    UNIQUE (org_code, version_no)
);

-- 关联语义目录版本（目录修订即产生新版本；旧版本保留供已运行沙箱溯源）
CREATE TABLE join_catalog (
    id              BIGSERIAL PRIMARY KEY,
    version_no      INT  NOT NULL UNIQUE,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / SUPERSEDED
    revision_reason TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 目录连接边：主外键、有效区间、边界规则、维表修订频率、期望扇出；
-- version_intervals 为目录创建时从维表快照的区间+合并信息（目录即语义快照）
CREATE TABLE join_catalog_edge (
    id                BIGSERIAL PRIMARY KEY,
    catalog_id        BIGINT NOT NULL REFERENCES join_catalog(id),
    edge_name         TEXT NOT NULL,      -- 如 fee_detail__dim_org_version
    fact_table        TEXT NOT NULL,
    fact_fk           TEXT NOT NULL,      -- 事实表外键列
    dim_table         TEXT NOT NULL,
    dim_key           TEXT NOT NULL,      -- 维表主键/版本键
    join_semantics    TEXT NOT NULL,      -- EQUI / TEMPORAL_RANGE / TEMPORAL_MAP
    valid_from_col    TEXT,               -- 有效区间列（时态边）
    valid_to_col      TEXT,
    fact_date_col     TEXT,               -- 事实表结算日列
    boundary_rule     TEXT,               -- CLOSED_OPEN（闭开约定）
    merge_rule        TEXT,               -- SUCCESSOR（合并路由后继机构）/ STRICT
    dim_revision_freq TEXT NOT NULL,      -- LOW / MEDIUM / HIGH（维表修订频率档位）
    expected_fanout   NUMERIC(8,4) NOT NULL DEFAULT 1.0,
    version_intervals JSONB,              -- {"intervals":{org:[{v,from,to,name}]}, "merges":{org:{mergedInto,effective}}}
    note              TEXT,
    UNIQUE (catalog_id, edge_name)
);

-- 映射构建批次：方案B（按日期展开的有效映射）的构建记录，版本依赖到目录
CREATE TABLE org_map_build (
    id          BIGSERIAL PRIMARY KEY,
    catalog_id  BIGINT NOT NULL REFERENCES join_catalog(id),
    from_date   DATE NOT NULL,
    to_date     DATE NOT NULL,
    row_count   BIGINT,
    status      TEXT NOT NULL DEFAULT 'CURRENT',  -- CURRENT / STALE（目录修订后滞后）
    built_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 按日期展开的有效映射（平台派生、append-only；查询按 build_id 固定版本）
CREATE TABLE org_date_version_map (
    org_code         TEXT NOT NULL,    -- 结算行上的机构代码
    day              DATE NOT NULL,
    matched_org_code TEXT NOT NULL,    -- 归属机构（合并后继路由后可能不同于 org_code）
    version_no       INT  NOT NULL,
    build_id         BIGINT NOT NULL REFERENCES org_map_build(id),
    PRIMARY KEY (org_code, day, build_id)
);

-- 有效期匹配运行（版本依赖：目录 + 快照 + 映射构建 + 模式）
CREATE TABLE match_run (
    id           BIGSERIAL PRIMARY KEY,
    catalog_id   BIGINT NOT NULL REFERENCES join_catalog(id),
    snapshot_id  BIGINT REFERENCES data_snapshot(id),
    mode         TEXT NOT NULL,        -- RANGE（查询时时态范围匹配）/ MAP（展开映射查找）
    map_build_id BIGINT REFERENCES org_map_build(id),
    scope        JSONB NOT NULL,       -- {"dateFrom":..,"dateTo":..,"orgCode":..} 或单笔 {"orgCode":..,"settleDate":..}
    matched      INT NOT NULL DEFAULT 0,
    successor    INT NOT NULL DEFAULT 0,
    unmatched    INT NOT NULL DEFAULT 0,
    conflicts    INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 每笔结算的可解释机构版本归属
CREATE TABLE match_result (
    id               BIGSERIAL PRIMARY KEY,
    match_run_id     BIGINT NOT NULL REFERENCES match_run(id),
    fee_id           BIGINT,           -- 结算明细 id（单笔指定机构+日期时为空）
    org_code         TEXT NOT NULL,    -- 结算行上的机构代码
    settle_date      DATE NOT NULL,
    status           TEXT NOT NULL,    -- MATCHED / MATCHED_SUCCESSOR / UNMATCHED / CONFLICT
    matched_org_code TEXT,             -- 归属机构（后继路由后）
    version_no       INT,
    evidence         JSONB NOT NULL    -- 候选版本、闭开取舍依据、合并路由依据
);

-- 连接基数模型：每条连接边 × 参数桶的期望与观察行数
CREATE TABLE join_cardinality_stat (
    id             BIGSERIAL PRIMARY KEY,
    catalog_id     BIGINT NOT NULL REFERENCES join_catalog(id),
    edge_name      TEXT NOT NULL,
    bucket_key     TEXT NOT NULL,      -- 参数桶，如 2025-11..2026-02|org=ANY|status=ANY
    side           TEXT NOT NULL,      -- ORIGINAL / CANDIDATE
    semantics      TEXT NOT NULL,      -- 该侧实际使用的连接语义（TEMPORAL_RANGE / TEMPORAL_MAP / ORG_ONLY ...）
    expected_rows  BIGINT NOT NULL,
    observed_rows  BIGINT NOT NULL,
    fanout         NUMERIC(12,4) NOT NULL,
    status         TEXT NOT NULL,      -- OK / INFLATED / DEFICIT
    sandbox_run_id BIGINT,
    measured_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 改写沙箱运行：固定参数 + 冻结快照 + 目录快照下运行原语句与候选
CREATE TABLE sandbox_run (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT,
    snapshot_id     BIGINT NOT NULL REFERENCES data_snapshot(id),
    catalog_id      BIGINT NOT NULL REFERENCES join_catalog(id),
    map_build_id    BIGINT REFERENCES org_map_build(id),
    candidate_id    BIGINT REFERENCES rewrite_candidate(id),  -- 关联发布轨道的候选（可空）
    params          JSONB NOT NULL,
    original_sql    TEXT NOT NULL,
    candidate_sql   TEXT NOT NULL,
    candidate_label TEXT,
    edge_semantics  JSONB NOT NULL,    -- {"edgeName":{"original":"TEMPORAL_RANGE","candidate":"ORG_ONLY"}}
    status          TEXT NOT NULL DEFAULT 'RUN',  -- RUN / CATALOG_STALE（目录修订后保留原目录快照）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 沙箱每侧执行结果
CREATE TABLE sandbox_execution (
    id             BIGSERIAL PRIMARY KEY,
    sandbox_run_id BIGINT NOT NULL REFERENCES sandbox_run(id),
    side           TEXT NOT NULL,      -- ORIGINAL / CANDIDATE
    status         TEXT NOT NULL,      -- SUCCESS / FAILED / TIMEOUT
    row_count      BIGINT,
    total_amount   NUMERIC(18,2),
    org_group      JSONB,              -- 机构级金额分组 {"ORG001": 123.45}
    pk_list        JSONB,              -- 明细主键集合（明细型语句）
    version_map    JSONB,              -- 版本归属 {id: version_no}（明细型且含 version_no 列）
    edge_rows      JSONB,              -- 每条连接边的观察行数 {"fee_detail__dim_org_version": 4800}
    elapsed_ms     BIGINT,
    error          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 重复行归因报告：把金额或行数膨胀定位至具体连接边
CREATE TABLE dup_attribution (
    id              BIGSERIAL PRIMARY KEY,
    sandbox_run_id  BIGINT NOT NULL REFERENCES sandbox_run(id),
    catalog_id      BIGINT NOT NULL REFERENCES join_catalog(id),
    verdict         TEXT NOT NULL,     -- PASS / BLOCK
    row_count_equal BOOLEAN,
    amount_equal    BOOLEAN,
    org_group_equal BOOLEAN,
    version_equal   BOOLEAN,
    org_amount_diff JSONB,             -- 机构金额差异 [{"org":..,"original":..,"candidate":..,"ratio":..}]
    version_diff    JSONB,             -- 版本归属差异样例 [{"id":..,"original":1,"candidate":2}]
    inflation_path  JSONB,             -- [{"edge":..,"expectedFanout":..,"observedFanout":..,"duplicatedOrgs":[...]}]
    release_decision TEXT NOT NULL,    -- ELIGIBLE / BLOCKED
    detail          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_catalog_edge_catalog  ON join_catalog_edge (catalog_id);
CREATE INDEX idx_map_org_day           ON org_date_version_map (org_code, day);
CREATE INDEX idx_match_result_run      ON match_result (match_run_id);
CREATE INDEX idx_cardinality_edge      ON join_cardinality_stat (catalog_id, edge_name, bucket_key);
CREATE INDEX idx_sandbox_exec_run      ON sandbox_execution (sandbox_run_id);
CREATE INDEX idx_dup_attr_run          ON dup_attribution (sandbox_run_id);
