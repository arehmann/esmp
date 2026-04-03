#!/usr/bin/env bash
#
# ESMP Data Backup — exports Neo4j graph + MySQL + recipe book to a git repo
#
# Usage:
#   ./scripts/backup/backup.sh [BACKUP_REPO_PATH]
#
# If BACKUP_REPO_PATH is not provided, defaults to ../esmp-backup
# The backup repo is created and initialized automatically on first run.
#
# Schedule nightly:
#   crontab: 0 2 * * * cd /path/to/esmp && ./scripts/backup/backup.sh
#   Windows Task Scheduler: bash -c "cd /c/frontoffice/esmp && ./scripts/backup/backup.sh"

set -euo pipefail

# --- Configuration (override via environment) ---
BACKUP_REPO="${1:-${ESMP_BACKUP_REPO:-../esmp-backup}}"
NEO4J_HOST="${NEO4J_HOST:-localhost}"
NEO4J_BOLT_PORT="${NEO4J_BOLT_PORT:-7687}"
NEO4J_HTTP_PORT="${NEO4J_HTTP_PORT:-7474}"
NEO4J_USER="${NEO4J_USER:-neo4j}"
NEO4J_PASS="${NEO4J_PASS:-esmp-local-password}"
MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3307}"
MYSQL_USER="${MYSQL_USER:-esmp}"
MYSQL_PASS="${MYSQL_PASS:-esmp-local-password}"
MYSQL_DB="${MYSQL_DB:-esmp}"
ESMP_URL="${ESMP_URL:-http://localhost:8080}"
QDRANT_URL="${QDRANT_URL:-http://localhost:6333}"

TIMESTAMP=$(date +%Y-%m-%d_%H-%M-%S)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== ESMP Backup — $TIMESTAMP ==="
echo "Backup repo: $BACKUP_REPO"

# --- Initialize backup repo if needed ---
if [ ! -d "$BACKUP_REPO" ]; then
    echo "Creating backup repo at $BACKUP_REPO"
    mkdir -p "$BACKUP_REPO"
    cd "$BACKUP_REPO"
    git init
    echo "# ESMP Data Backup" > README.md
    echo "Automated backups of ESMP knowledge graph, recipe book, and database." >> README.md
    git add README.md && git commit -m "init: ESMP backup repo"
    cd - > /dev/null
fi

mkdir -p "$BACKUP_REPO"/{neo4j,mysql,recipe-book,lexicon,config}

# --- 1. Neo4j: Export all nodes and relationships as Cypher ---
echo ""
echo "[1/5] Exporting Neo4j graph..."

# Export nodes by label
NEO4J_CYPHER_URL="http://${NEO4J_HOST}:${NEO4J_HTTP_PORT}/db/neo4j/tx/commit"
NEO4J_AUTH=$(echo -n "${NEO4J_USER}:${NEO4J_PASS}" | base64)

run_cypher() {
    local query="$1"
    local output="$2"
    curl -s -X POST "$NEO4J_CYPHER_URL" \
        -H "Content-Type: application/json" \
        -H "Authorization: Basic ${NEO4J_AUTH}" \
        -d "{\"statements\":[{\"statement\":\"$query\"}]}" \
        > "$output"
}

# Export all nodes with properties as JSON
run_cypher "MATCH (n) RETURN labels(n) AS labels, properties(n) AS props, elementId(n) AS id" \
    "$BACKUP_REPO/neo4j/nodes.json"

# Export all relationships
run_cypher "MATCH (a)-[r]->(b) RETURN type(r) AS type, properties(r) AS props, properties(a) AS from_props, labels(a) AS from_labels, properties(b) AS to_props, labels(b) AS to_labels" \
    "$BACKUP_REPO/neo4j/relationships.json"

# Export node counts for quick validation
run_cypher "MATCH (n) RETURN labels(n)[0] AS label, count(n) AS count ORDER BY count DESC" \
    "$BACKUP_REPO/neo4j/node-counts.json"

# Export relationship counts
run_cypher "MATCH ()-[r]->() RETURN type(r) AS type, count(r) AS count ORDER BY count DESC" \
    "$BACKUP_REPO/neo4j/relationship-counts.json"

NODE_COUNT=$(cat "$BACKUP_REPO/neo4j/node-counts.json" | grep -o '"count":[0-9]*' | head -20 | sed 's/"count"://' | paste -sd+ | bc 2>/dev/null || echo "?")
echo "  Exported $NODE_COUNT nodes"

# --- 2. MySQL dump ---
echo ""
echo "[2/5] Exporting MySQL..."

docker exec esmp-mysql-1 mysqldump \
    -u "$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" \
    --single-transaction --routines --triggers \
    2>/dev/null > "$BACKUP_REPO/mysql/esmp.sql" || \
docker exec esmp_mysql_1 mysqldump \
    -u "$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" \
    --single-transaction --routines --triggers \
    2>/dev/null > "$BACKUP_REPO/mysql/esmp.sql" || \
echo "  WARNING: MySQL export failed (container name mismatch — check 'docker ps')"

echo "  MySQL dump: $(wc -l < "$BACKUP_REPO/mysql/esmp.sql" 2>/dev/null || echo 0) lines"

# --- 3. Recipe book (already JSON — just copy) ---
echo ""
echo "[3/5] Backing up recipe book..."

# From ESMP API (has enriched usageCounts)
curl -s "${ESMP_URL}/api/migration/recipe-book" \
    > "$BACKUP_REPO/recipe-book/all-rules.json" 2>/dev/null || echo "[]" > "$BACKUP_REPO/recipe-book/all-rules.json"

# Gaps separately for quick reference
curl -s "${ESMP_URL}/api/migration/recipe-book/gaps" \
    > "$BACKUP_REPO/recipe-book/gaps.json" 2>/dev/null || echo "[]" > "$BACKUP_REPO/recipe-book/gaps.json"

# Copy the runtime file if accessible
if [ -f "data/migration/vaadin-recipe-book.json" ]; then
    cp "data/migration/vaadin-recipe-book.json" "$BACKUP_REPO/recipe-book/runtime-file.json"
fi

RULE_COUNT=$(grep -o '"id"' "$BACKUP_REPO/recipe-book/all-rules.json" 2>/dev/null | wc -l || echo 0)
echo "  $RULE_COUNT rules backed up"

# --- 4. Curated lexicon terms ---
echo ""
echo "[4/5] Backing up curated business terms..."

curl -s "${ESMP_URL}/api/lexicon/?curated=true" \
    > "$BACKUP_REPO/lexicon/curated-terms.json" 2>/dev/null || echo "[]" > "$BACKUP_REPO/lexicon/curated-terms.json"

TERM_COUNT=$(grep -o '"termId"' "$BACKUP_REPO/lexicon/curated-terms.json" 2>/dev/null | wc -l || echo 0)
echo "  $TERM_COUNT curated terms"

# --- 5. Validation snapshot (for comparison after restore) ---
echo ""
echo "[5/5] Saving validation snapshot..."

curl -s "${ESMP_URL}/api/graph/validation" \
    > "$BACKUP_REPO/config/validation-snapshot.json" 2>/dev/null || echo "{}" > "$BACKUP_REPO/config/validation-snapshot.json"

# Save source status
curl -s "${ESMP_URL}/api/source/status" \
    > "$BACKUP_REPO/config/source-status.json" 2>/dev/null || echo "{}" > "$BACKUP_REPO/config/source-status.json"

# --- Write manifest ---
cat > "$BACKUP_REPO/MANIFEST.md" << EOF
# ESMP Backup — $TIMESTAMP

| Item | File | Records |
|------|------|---------|
| Neo4j nodes | neo4j/nodes.json | $NODE_COUNT |
| Neo4j relationships | neo4j/relationships.json | - |
| MySQL dump | mysql/esmp.sql | $(wc -l < "$BACKUP_REPO/mysql/esmp.sql" 2>/dev/null || echo 0) lines |
| Recipe book rules | recipe-book/all-rules.json | $RULE_COUNT |
| Recipe book gaps | recipe-book/gaps.json | - |
| Curated terms | lexicon/curated-terms.json | $TERM_COUNT |
| Validation snapshot | config/validation-snapshot.json | - |

## Restore

\`\`\`bash
./scripts/backup/restore.sh $BACKUP_REPO
\`\`\`
EOF

# --- Commit to git ---
echo ""
echo "Committing to backup repo..."
cd "$BACKUP_REPO"
git add -A
git commit -m "backup: $TIMESTAMP — ${NODE_COUNT} nodes, ${RULE_COUNT} rules, ${TERM_COUNT} curated terms" \
    2>/dev/null || echo "No changes to commit"

# Push if remote is configured
if git remote get-url origin &>/dev/null; then
    git push origin main 2>/dev/null || git push origin master 2>/dev/null || echo "Push failed — check remote"
fi

cd - > /dev/null

echo ""
echo "=== Backup complete ==="
echo "Location: $BACKUP_REPO"
echo "Commit: $(cd "$BACKUP_REPO" && git log --oneline -1)"
