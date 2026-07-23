#!/usr/bin/env bash
#
# 确定性端到端测试流程（无需 LLM / Postgres / Docker / 网络）。
# 边界（LLM/Git/Jira）用桩替换，但向量存储用真实的嵌入式 DuckDB + 真实 embedding 模型，
# 因此覆盖：三种 Agent 模式编排、C1 数据流、DuckDB 持久化、RAG 检索、fail-soft。
#
# 用法：  bash scripts/e2e-test.sh
# 退出码：0 全过 / 非 0 有失败（可直接用于 CI 与 /ai-test-report 的阶段 1 门禁）。
#
set -euo pipefail
cd "$(dirname "$0")/.."

echo "▶ 运行确定性流水线测试（real DuckDB, stubbed LLM/Git/Jira）…"
./gradlew test --console=plain

python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
tot = fail = err = skip = 0
pipeline = []
for f in glob.glob('build/test-results/test/TEST-*.xml'):
    r = ET.parse(f).getroot()
    t, fa, e, s = (int(r.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped'))
    tot += t; fail += fa; err += e; skip += s
    cls = f.split('TEST-')[-1].rsplit('.xml', 1)[0]
    if any(k in cls for k in ('e2e.', 'ReportPromptBuilder', 'DuckDb', 'MultiAgent', 'Coordinator', 'SubAgentDelegation')):
        pipeline.append((cls.split('.')[-1], t, fa + e))
print('\n──────── 流水线相关 ────────')
for name, t, bad in sorted(pipeline):
    print(f'  {"✅" if bad == 0 else "❌"} {name}: {t} tests, {bad} failed')
print('────────────────────────────')
print(f'总计: {tot} tests, {fail} failures, {err} errors, {skip} skipped')
print('HTML 报告: build/reports/tests/test/index.html')
raise SystemExit(1 if (fail or err) else 0)
PY

echo "✅ 确定性端到端测试全部通过。"
