-- =====================================================================
-- q05_window.sql  窗口函数实战: 行转列 / TopN / 连续登录 / 留存率
-- 配套答卷: Q24-Q25
-- =====================================================================

USE interview_lab;

-- =====================================================================
-- 1. 行转列 — 演示用 users 数据模拟成绩表
-- =====================================================================
-- 临时构造成绩表
DROP TEMPORARY TABLE IF EXISTS tmp_scores;
CREATE TEMPORARY TABLE tmp_scores (
    name VARCHAR(50),
    subject VARCHAR(20),
    score INT
);

INSERT INTO tmp_scores VALUES
('张三','语文',85),('张三','数学',92),('张三','英语',78),
('李四','语文',90),('李四','数学',88),('李四','英语',95),
('王五','语文',76),('王五','数学',82),('王五','英语',89);

-- 行转列:每个学生各科成绩一列
SELECT
    name,
    MAX(CASE WHEN subject='语文' THEN score END) AS 语文,
    MAX(CASE WHEN subject='数学' THEN score END) AS 数学,
    MAX(CASE WHEN subject='英语' THEN score END) AS 英语,
    ROUND(AVG(score), 1) AS 平均分
FROM tmp_scores
GROUP BY name
ORDER BY 平均分 DESC;

-- =====================================================================
-- 2. TopN — 每个年龄段工资/年龄前三
-- =====================================================================
-- 用 users.age 模拟"分数"
SELECT * FROM (
    SELECT
        CASE
            WHEN age BETWEEN 20 AND 24 THEN '20-24'
            WHEN age BETWEEN 25 AND 29 THEN '25-29'
            WHEN age BETWEEN 30 AND 34 THEN '30-34'
            WHEN age BETWEEN 35 AND 40 THEN '35-40'
        END AS age_group,
        id, name, age,
        ROW_NUMBER() OVER (PARTITION BY
            CASE
                WHEN age BETWEEN 20 AND 24 THEN '20-24'
                WHEN age BETWEEN 25 AND 29 THEN '25-29'
                WHEN age BETWEEN 30 AND 34 THEN '30-34'
                WHEN age BETWEEN 35 AND 40 THEN '35-40'
            END
            ORDER BY age DESC, id ASC) AS rn
    FROM users
    WHERE age BETWEEN 20 AND 40
) t
WHERE rn <= 3
ORDER BY age_group, rn;

-- ROW_NUMBER vs RANK vs DENSE_RANK
SELECT
    name, age,
    ROW_NUMBER() OVER (ORDER BY age DESC) AS rn_row,
    RANK()       OVER (ORDER BY age DESC) AS rn_rank,
    DENSE_RANK() OVER (ORDER BY age DESC) AS rn_dense
FROM users
WHERE age >= 35
ORDER BY age DESC
LIMIT 20;

-- =====================================================================
-- 3. 连续 N 天登录 — 找连续登录 ≥ 7 天的用户(Q25 答案)
-- =====================================================================
-- 数据准备: user 10 连续 10 天
SELECT '=== 全部登录段 (含间断) ===' AS info;
WITH ranked AS (
    SELECT user_id, login_date,
           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY login_date) AS rn
    FROM login_logs
),
grouped AS (
    SELECT user_id, login_date,
           DATE_SUB(login_date, INTERVAL rn DAY) AS grp
    FROM ranked
)
SELECT user_id,
       MIN(login_date) AS start_date,
       MAX(login_date) AS end_date,
       COUNT(*)        AS continuous_days
FROM grouped
GROUP BY user_id, grp
ORDER BY user_id, start_date;

SELECT '=== 连续 ≥ 7 天的段 ===' AS info;
WITH ranked AS (
    SELECT user_id, login_date,
           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY login_date) AS rn
    FROM login_logs
),
grouped AS (
    SELECT user_id, login_date,
           DATE_SUB(login_date, INTERVAL rn DAY) AS grp
    FROM ranked
)
SELECT user_id,
       MIN(login_date) AS start_date,
       MAX(login_date) AS end_date,
       COUNT(*)        AS continuous_days
FROM grouped
GROUP BY user_id, grp
HAVING COUNT(*) >= 7
ORDER BY continuous_days DESC, user_id;

-- =====================================================================
-- 4. 留存率 — 7 日留存 / 30 日留存
-- =====================================================================
-- 注册后第 N 天再次登录的用户比例
-- 用 users.create_time 模拟"注册时间"
DROP TEMPORARY TABLE IF EXISTS tmp_register;
CREATE TEMPORARY TABLE tmp_register AS
SELECT id AS user_id, DATE(create_time) AS register_date
FROM users
WHERE create_time BETWEEN '2026-09-01' AND '2026-10-01';

-- 30 日留存: 注册后 30 天内登录过
SELECT
    COUNT(DISTINCT r.user_id) AS registered_users,
    COUNT(DISTINCT CASE
        WHEN DATEDIFF(l.login_date, r.register_date) BETWEEN 1 AND 30
        THEN l.user_id
    END) AS retained_users,
    ROUND(
        COUNT(DISTINCT CASE
            WHEN DATEDIFF(l.login_date, r.register_date) BETWEEN 1 AND 30
            THEN l.user_id
        END) * 100.0 / NULLIF(COUNT(DISTINCT r.user_id), 0),
    2) AS retention_30d_pct
FROM tmp_register r
LEFT JOIN login_logs l ON r.user_id = l.user_id;

-- =====================================================================
-- 5. 累计求和 — 每月新增用户的累计值
-- =====================================================================
WITH monthly AS (
    SELECT DATE_FORMAT(create_time, '%Y-%m') AS month, COUNT(*) AS new_users
    FROM users
    GROUP BY DATE_FORMAT(create_time, '%Y-%m')
)
SELECT month, new_users,
       SUM(new_users) OVER (ORDER BY month) AS cumulative_users
FROM monthly
ORDER BY month;