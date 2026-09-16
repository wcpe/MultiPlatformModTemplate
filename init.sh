#!/usr/bin/env bash
set -euo pipefail

# 一键初始化模板 — 零 JDK 依赖（不走 Gradle，只需 bash + perl + coreutils），兼容 Git Bash / Linux / macOS
# 覆盖换名的全部动作：文本替换 + 源码包目录搬迁 + Service 描述符重命名
# 用法:
#   ./init.sh                                   # 交互式
#   ./init.sh --id mygame --group com.example.mygame --name MyGame
#   ./init.sh --id mygame --group com.example.mygame --name MyGame --rewrite-channels
#   ./init.sh --dry-run --id mygame --group com.example.mygame --name MyGame  # 预览不写盘
#
# 本脚本不需要能跑通 Gradle 即可换名，用于打破"必须先装好 JDK 25 + 全量构建配置成功才能换名"的鸡生蛋问题。

OLD_GROUP="top.wcpe.mc.mpmt"
OLD_PACKAGE_PATH="top/wcpe/mc/mpmt"
OLD_DISPLAY_NAME="MultiPlatformModTemplate"
OLD_ID="mpmt"
OLD_ROOT_NAME="mpmt"

SKIP_DIRS=(
  ".git" ".gradle" "build" ".tmp" "node_modules" ".idea"
  "run" "run-client" "run-server" "logs"
  "run-acceptance-server" "run-acceptance-client" "run-realserver"
)

# 文本候选后缀（与 Kotlin 版保持一致）
TEXT_SUFFIXES=(
  ".java" ".kt" ".kts" ".gradle" ".properties"
  ".yml" ".yaml" ".json" ".toml" ".md" ".xml" ".txt" ".MF" ".services"
)
SPECIAL_NAMES=(
  "plugin.yml" "mods.toml" "fabric.mod.json" "VERSION" "mcmod.info"
)

DRY_RUN="false"
REWRITE_CHANNELS="false"
NEW_ID=""
NEW_GROUP=""
NEW_NAME=""
YES="false"

RED="\033[31m"
GREEN="\033[32m"
YELLOW="\033[33m"
CYAN="\033[36m"
RESET="\033[0m"

info()  { echo -e "${CYAN}[init]${RESET} $*"; }
warn()  { echo -e "${YELLOW}[warn]${RESET} $*"; }
ok()    { echo -e "${GREEN}[ok]${RESET} $*"; }
err()   { echo -e "${RED}[error]${RESET} $*" >&2; }

usage() {
  cat <<'USAGE'
用法: ./init.sh [选项]

选项:
  --id <id>                新 modId / 短 id，需匹配 [a-z][a-z0-9_]{1,31}  (例: mygame)
  --group <group>          新 Maven group + Java 包根，至少两段       (例: com.example.mygame)
  --name <name>            新展示名                                    (例: MyGame)
  --rewrite-channels       同时重写协议通道 mpmt:main / MPMT 等（产品化时用）
  --dry-run                只预览不写盘
  --yes, -y                非交互式，缺参数直接报错（用于 CI）
  -h, --help               显示此帮助

不带参数直接运行则进入交互式提问。
USAGE
}

# ---- 参数解析 ----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --id) NEW_ID="${2:-}"; shift 2 ;;
    --group) NEW_GROUP="${2:-}"; shift 2 ;;
    --name) NEW_NAME="${2:-}"; shift 2 ;;
    --rewrite-channels) REWRITE_CHANNELS="true"; shift ;;
    --dry-run) DRY_RUN="true"; shift ;;
    --yes|-y) YES="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    --) shift; break ;;
    -*) err "未知选项: $1"; usage; exit 1 ;;
    *) err "未知参数: $1"; usage; exit 1 ;;
  esac
done

# ---- 前置校验 ----
if [[ ! -f "settings.gradle.kts" ]]; then
  err "不像仓库根：当前目录缺少 settings.gradle.kts"
  err "请在模板仓库根目录运行 ./init.sh"
  exit 1
fi

# ---- 校验函数 ----
validate_id() {
  if ! [[ "$1" =~ ^[a-z][a-z0-9_]{1,31}$ ]]; then
    err "id 须匹配 [a-z][a-z0-9_]{1,31}（Fabric/Forge modid 友好），当前: $1"
    exit 1
  fi
}
validate_group() {
  if ! [[ "$1" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]]; then
    err "group 须为合法 Java 包名，至少两段（如 com.example.mygame），当前: $1"
    exit 1
  fi
}

# ---- 交互式补齐 ----
need_interactive="false"
if [[ -z "$NEW_ID" || -z "$NEW_GROUP" || -z "$NEW_NAME" ]]; then
  if [[ ! -t 0 ]]; then
    # 非交互环境（管道 / CI）缺参数直接报错，不进入 read（避免 EOF 死循环）
    [[ -z "$NEW_ID" ]] && err "缺少 --id（非交互环境须显式传参）" && exit 1
    [[ -z "$NEW_GROUP" ]] && err "缺少 --group（非交互环境须显式传参）" && exit 1
    [[ -z "$NEW_NAME" ]] && err "缺少 --name（非交互环境须显式传参）" && exit 1
  elif [[ "$YES" == "true" ]]; then
    [[ -z "$NEW_ID" ]] && err "缺少 --id" && exit 1
    [[ -z "$NEW_GROUP" ]] && err "缺少 --group" && exit 1
    [[ -z "$NEW_NAME" ]] && err "缺少 --name" && exit 1
  else
    need_interactive="true"
  fi
fi

if [[ "$need_interactive" == "true" ]]; then
  echo ""
  echo -e "${CYAN}=== 模板初始化 ===${RESET}"
  echo "将把模板身份从 mpmt / top.wcpe.mc.mpmt / MultiPlatformModTemplate 改为你的项目。"
  echo ""

  if [[ -z "$NEW_ID" ]]; then
    while true; do
      read -r -p "新 id (例: mygame) [a-z][a-z0-9_]{1,31}: " input || true
      input="$(echo "$input" | tr -d '\r' | xargs 2>/dev/null || echo "$input")"
      if [[ -z "$input" ]]; then warn "不能为空"; continue; fi
      if [[ "$input" =~ ^[a-z][a-z0-9_]{1,31}$ ]]; then NEW_ID="$input"; break; else warn "格式不对，需匹配 [a-z][a-z0-9_]{1,31}"; fi
    done
  fi
  if [[ -z "$NEW_GROUP" ]]; then
    while true; do
      read -r -p "新 group (例: com.example.mygame): " input || true
      input="$(echo "$input" | tr -d '\r' | xargs 2>/dev/null || echo "$input")"
      if [[ -z "$input" ]]; then warn "不能为空"; continue; fi
      if [[ "$input" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]]; then NEW_GROUP="$input"; break; else warn "须为合法 Java 包名，至少两段"; fi
    done
  fi
  if [[ -z "$NEW_NAME" ]]; then
    while true; do
      read -r -p "新展示名 (例: MyGame): " input || true
      # 展示名允许空格，但去除首尾
      input="$(echo "$input" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | tr -d '\r')"
      if [[ -z "$input" ]]; then warn "不能为空"; continue; fi
      NEW_NAME="$input"; break
    done
  fi

  if [[ "$REWRITE_CHANNELS" == "false" ]]; then
    read -r -p "是否同时重写协议通道 mpmt:main / MPMT ? [y/N]: " rc || true
    rc="$(echo "$rc" | tr -d '\r' | tr '[:upper:]' '[:lower:]')"
    if [[ "$rc" == "y" || "$rc" == "yes" ]]; then
      REWRITE_CHANNELS="true"
    fi
  fi

  # 一次确认：默认先 dry-run 预览，预览后再去掉 --dry-run 正式写盘
  if [[ "$DRY_RUN" == "false" ]]; then
    echo ""
    echo "将执行："
    echo "  id:    $OLD_ID -> $NEW_ID"
    echo "  group: $OLD_GROUP -> $NEW_GROUP"
    echo "  name:  $OLD_DISPLAY_NAME -> $NEW_NAME"
    echo "  通道:  $( [[ "$REWRITE_CHANNELS" == "true" ]] && echo "重写" || echo "保留 mpmt:main / MPMT" )"
    echo ""
    info "默认先进入 DRY-RUN 预览；确认清单无误后，去掉 --dry-run 再跑一次正式写盘。"
  fi
fi

# 最终校验
validate_id "$NEW_ID"
validate_group "$NEW_GROUP"
if [[ -z "$NEW_NAME" ]]; then err "展示名不能为空"; exit 1; fi
if [[ "$NEW_ID" == "$OLD_ID" && "$NEW_GROUP" == "$OLD_GROUP" && "$NEW_NAME" == "$OLD_DISPLAY_NAME" ]]; then
  err "新旧身份相同，无需改名"
  exit 1
fi

NEW_PACKAGE_PATH="${NEW_GROUP//./\/}"

# Legacy 通道名（与 Kotlin 版一致）
if [[ "$REWRITE_CHANNELS" == "true" ]]; then
  # 取前 4 位大写，不足补 X
  LEGACY="$(echo "$NEW_ID" | tr '[:lower:]' '[:upper:]' | cut -c1-4)"
  while [[ ${#LEGACY} -lt 4 ]]; do LEGACY="${LEGACY}X"; done
  LEGACY_TEST="$(echo "${NEW_ID}TEST" | tr '[:lower:]' '[:upper:]' | cut -c1-8)"
else
  LEGACY=""
  LEGACY_TEST=""
fi

ROOT_DIR="$(pwd -P)"
info "root=$ROOT_DIR"
info "  id:    $OLD_ID -> $NEW_ID"
info "  group: $OLD_GROUP -> $NEW_GROUP"
info "  name:  $OLD_DISPLAY_NAME -> $NEW_NAME"
info "  通道:  $( [[ "$REWRITE_CHANNELS" == "true" ]] && echo "重写 ($LEGACY / $LEGACY_TEST)" || echo "保留 mpmt:main / MPMT")"
info "  模式:  $( [[ "$DRY_RUN" == "true" ]] && echo "DRY-RUN" || echo "WRITE")"

# ---- 批量文本替换：单 perl 进程处理全部候选文件 ----
# 性能要求：Windows Git Bash 下每个子进程约 100ms，逐文件起进程会让 1100+ 文件跑到十分钟以上。
# 故：候选筛选用 bash 内建（无子进程），替换用一个 perl 进程批量完成。
# 变量经环境变量传入 perl，避免引号转义问题（展示名可含空格）。
apply_perl_batch() {
  local list_file="$1"
  OLD_GROUP="$OLD_GROUP" \
  OLD_PACKAGE_PATH="$OLD_PACKAGE_PATH" \
  NEW_GROUP="$NEW_GROUP" \
  NEW_PACKAGE_PATH="$NEW_PACKAGE_PATH" \
  OLD_DISPLAY_NAME="$OLD_DISPLAY_NAME" \
  NEW_NAME="$NEW_NAME" \
  OLD_ID="$OLD_ID" \
  NEW_ID="$NEW_ID" \
  OLD_ROOT_NAME="$OLD_ROOT_NAME" \
  REWRITE_CHANNELS="$REWRITE_CHANNELS" \
  LEGACY="$LEGACY" \
  LEGACY_TEST="$LEGACY_TEST" \
  DRY_RUN="$DRY_RUN" \
  FILE_LIST="$list_file" \
  perl -e '
    use strict;
    use warnings;

    my $oldGroup    = $ENV{OLD_GROUP};
    my $oldPath     = $ENV{OLD_PACKAGE_PATH};
    my $newGroup    = $ENV{NEW_GROUP};
    my $newPath     = $ENV{NEW_PACKAGE_PATH};
    my $oldName     = $ENV{OLD_DISPLAY_NAME};
    my $newName     = $ENV{NEW_NAME};
    my $oldId       = $ENV{OLD_ID};
    my $newId       = $ENV{NEW_ID};
    my $oldRoot     = $ENV{OLD_ROOT_NAME};
    my $rewrite     = $ENV{REWRITE_CHANNELS};
    my $legacy      = $ENV{LEGACY};
    my $legacyTest  = $ENV{LEGACY_TEST};
    my $dry         = ($ENV{DRY_RUN} eq "true");

    # 正则转义形式（如 SpotBugs 过滤器里的包名模式 `top\.wcpe\.mc\.mpmt`）：必须整段替换，
    # 否则只命中 id 词规则，会拼出"旧前缀 + 新 id"的坏模式。
    (my $oldGroupEsc = $oldGroup) =~ s/\./\\./g;
    (my $newGroupEsc = $newGroup) =~ s/\./\\./g;

    open(my $lf, "<", $ENV{FILE_LIST}) or die "无法读取候选列表: $!\n";
    while (my $f = <$lf>) {
      chomp $f;
      next unless -f $f;
      open(my $in, "<:raw", $f) or next;
      my $c = do { local $/; <$in> };
      close $in;
      next unless defined $c;
      next if index($c, "\0") >= 0;    # 二进制文件跳过

      my $o = $c;
      $o =~ s/\Q$oldGroupEsc\E/$newGroupEsc/g;
      $o =~ s/\Q$oldGroup\E/$newGroup/g;
      $o =~ s/\Q$oldPath\E/$newPath/g;
      $o =~ s/\Q$oldName\E/$newName/g;
      $o =~ s/\b\Q$oldId\E-/$newId-/g;
      $o =~ s/"\Q$oldId\E-/"$newId-/g;
      $o =~ s/\x27\Q$oldId\E-/\x27$newId-/g;
      $o =~ s/rootProject\.name\s*=\s*"\Q$oldRoot\E"/rootProject.name = "$newId"/g;
      $o =~ s/rootProject\.name\s*=\s*\x27\Q$oldRoot\E\x27/rootProject.name = \x27$newId\x27/g;
      $o =~ s/("id"\s*:\s*")\Q$oldId\E(")/$1$newId$2/g;
      $o =~ s/(modId\s*=\s*")\Q$oldId\E(")/$1$newId$2/g;
      $o =~ s/(modId\s*=\s*\x27)\Q$oldId\E(\x27)/$1$newId$2/g;
      if ($rewrite eq "true") {
        $o =~ s/\Q$oldId\E:main/$newId:main/g;
        $o =~ s/\Q$oldId\E-test:acceptance/$newId-test:acceptance/g;
        $o =~ s/\b"MPMT"/"$legacy"/g;
        $o =~ s/\b\x27MPMT\x27/\x27$legacy\x27/g;
        $o =~ s/\b"MPMTTEST"/"$legacyTest"/g;
        $o =~ s/\b\x27MPMTTEST\x27/\x27$legacyTest\x27/g;
      }
      $o =~ s/\b\Q$oldId\E\b/$newId/g;

      next if $o eq $c;
      print "$f\n";
      if (!$dry) {
        open(my $out, ">:raw", $f) or next;
        print $out $o;
        close $out;
      }
    }
    close $lf;
  '
}

# ---- 判断是否为文本候选 ----
# 性能：名字用 bash 参数展开取，不调用 basename（Windows 下每次子进程约 100ms）
is_text_candidate() {
  local file="$1"
  local name="${file##*/}"

  # 逐字节回归基线（wire-v1 golden）不参与文本替换：其 Base64 载荷是历史字节快照，
  # 只改其中的元数据字段会让基线与编码结果不一致，换名后协议测试必失败。
  if [[ "$file" == *"/src/test/resources/golden/"* ]]; then
    return 1
  fi
  # Service 描述符：META-INF/services/* 含旧 group
  if [[ "$file" == *"/META-INF/services/"* ]] && [[ "$name" == *"$OLD_GROUP"* ]]; then
    return 0
  fi
  for s in "${SPECIAL_NAMES[@]}"; do
    if [[ "$name" == "$s" ]]; then
      return 0
    fi
  done
  # 无扩展名则不是候选（已处理 SPECIAL_NAMES）
  if [[ "$name" != *.* ]]; then
    return 1
  fi
  local suffix=".${name##*.}"
  # Kotlin 版是精确匹配（含大小写），这里做大小写敏感
  for s in "${TEXT_SUFFIXES[@]}"; do
    if [[ "$suffix" == "$s" ]]; then
      return 0
    fi
  done
  return 1
}

# 跳过自身（换名完成后可删除本脚本）
should_skip_file() {
  local base="${1##*/}"
  if [[ "$base" == "init.sh" ]]; then return 0; fi
  return 1
}

# 计算文件名内的身份替换结果（只处理文件名，不含目录；规则与文本替换一致：
# 先整段 group，再把 id 作为独立词替换）
identity_basename() {
  OLD_GROUP="$OLD_GROUP" \
  NEW_GROUP="$NEW_GROUP" \
  OLD_ID="$OLD_ID" \
  NEW_ID="$NEW_ID" \
  NAME="$1" \
  perl -e '
    my $n = $ENV{NAME};
    my $oldGroup = $ENV{OLD_GROUP};
    my $newGroup = $ENV{NEW_GROUP};
    my $oldId    = $ENV{OLD_ID};
    my $newId    = $ENV{NEW_ID};
    $n =~ s/\Q$oldGroup\E/$newGroup/g;
    $n =~ s/\b\Q$oldId\E\b/$newId/g;
    print $n;
  '
}

# ---- 收集文件 ----
info "扫描文件..."

# 用 find + prune 排除跳过目录
# shellcheck disable=SC2044
changed_files=0
renamed_files=0
moved_packages=0

# 临时文件：候选清单
tmp_candidates="$(mktemp)"
trap 'rm -f "$tmp_candidates" 2>/dev/null; true' EXIT

# 遍历：find . \( prune部分 \) -prune -o -type f -print
# 为兼容 macOS 与 Git Bash，显式处理
find_prune_args=()
first=true
for d in "${SKIP_DIRS[@]}"; do
  if [[ "$first" == "true" ]]; then
    find_prune_args+=(-name "$d")
    first=false
  else
    find_prune_args+=(-o -name "$d")
  fi
done
find_cmd=(find . \( "${find_prune_args[@]}" \) -prune -o -type f -print)

# 执行遍历并收集候选（纯 bash 判断，不起子进程）
tmp_candidates="$(mktemp)"
while IFS= read -r file; do
  rel="${file#./}"
  if should_skip_file "$file"; then continue; fi
  if ! is_text_candidate "$file"; then continue; fi
  printf '%s\n' "$rel" >> "$tmp_candidates"
done < <("${find_cmd[@]}" | sort)

# 单进程批量替换：perl 输出实际发生变更的文件清单
changed_files=0
if [[ -s "$tmp_candidates" ]]; then
  while IFS= read -r rel; do
    echo "  TEXT $rel"
    changed_files=$((changed_files + 1))
  done < <(apply_perl_batch "$tmp_candidates")
fi

# ---- 文件名身份替换 ----
# 文本替换只改文件内容；文件名里的身份（`META-INF/services/<group>.<类>`、`mpmt.mixins.json` 等）
# 必须同步改名，否则清单 / 描述符指向的文件不存在，换名后打包校验会失败。
info "检查需改名的文件..."
while IFS= read -r file; do
  [[ -z "$file" ]] && continue
  if should_skip_file "$file"; then continue; fi
  base="${file##*/}"
  case "$base" in
    *"$OLD_GROUP"* | *"$OLD_ID"*) ;;
    *) continue ;;
  esac
  new_base="$(identity_basename "$base")"
  [[ "$new_base" == "$base" ]] && continue
  dest="${file%/*}/$new_base"
  echo "  MOVE ${file#./} -> ${dest#./}"
  renamed_files=$((renamed_files + 1))
  if [[ "$DRY_RUN" == "false" ]]; then
    mkdir -p "${dest%/*}"
    mv "$file" "$dest"
  fi
done < <("${find_cmd[@]}" | sort)

# ---- 源码包目录搬迁 ----
info "检查源码包目录..."

# 找出所有名为 mpmt 的目录，且相对路径包含 <srcRoot>/top/wcpe/mc/mpmt
# 源码根同时覆盖 java/ 与 kotlin/（build-logic 是 Kotlin 插件工程，漏搬会导致其编译失败）
java_candidates=()
while IFS= read -r dir; do
  rel="${dir#./}"
  rel_norm="${rel//\\//}"
  case "$rel_norm" in
    */java/top/wcpe/mc/mpmt|java/top/wcpe/mc/mpmt) java_candidates+=("$dir") ;;
    */kotlin/top/wcpe/mc/mpmt|kotlin/top/wcpe/mc/mpmt) java_candidates+=("$dir") ;;
  esac
done < <(find . \( "${find_prune_args[@]}" \) -prune -o -type d -name "mpmt" -print 2>/dev/null | sort -r)

# 按路径长度降序（最深优先），与 Kotlin 版一致；制表符分隔兼容含空格路径（禁用 find -printf，保证 macOS 可用）
if [[ ${#java_candidates[@]} -gt 0 ]]; then
  mapfile -t java_candidates < <(printf "%s\n" "${java_candidates[@]}" | awk '{ print length($0)"\t"$0 }' | sort -rn | cut -f2-)
fi

for oldDir in "${java_candidates[@]}"; do
  [[ -z "$oldDir" ]] && continue
  if [[ ! -d "$oldDir" ]]; then continue; fi
  # 向上找源码根（java 或 kotlin，用参数展开，不调 basename/dirname：Windows 下每次子进程约 66ms）
  javaRoot="$oldDir"
  # oldDir 形如 ./core/domain/src/main/java/top/wcpe/mc/mpmt
  # 向上直到路径末段 == java 或 kotlin
  while [[ "${javaRoot##*/}" != "java" && "${javaRoot##*/}" != "kotlin" && "$javaRoot" != "." && "$javaRoot" != "/" && -n "$javaRoot" ]]; do
    javaRoot="${javaRoot%/*}"
  done
  if [[ "${javaRoot##*/}" != "java" && "${javaRoot##*/}" != "kotlin" ]]; then
    warn "跳过无法定位源码根的目录: $oldDir"
    continue
  fi
  # 新目录 = javaRoot + newGroup 路径
  newDir="$javaRoot"
  IFS='.' read -ra segs <<< "$NEW_GROUP"
  for seg in "${segs[@]}"; do
    newDir="$newDir/$seg"
  done

  rel_old="${oldDir#./}"
  rel_new="${newDir#./}"
  echo "  MOVE $rel_old -> $rel_new"
  moved_packages=$((moved_packages + 1))
  if [[ "$DRY_RUN" == "true" ]]; then
    continue
  fi
  mkdir -p "${newDir%/*}"
  if [[ -e "$newDir" ]]; then
    # 合并：把 oldDir 下内容逐个搬过去（含隐藏文件，nullglob/dotglob 保证空目录不展开）
    shopt -s nullglob dotglob
    for child in "$oldDir"/*; do
      base="${child##*/}"
      if [[ "$base" == "." || "$base" == ".." ]]; then continue; fi
      dest="$newDir/$base"
      if [[ -e "$dest" ]]; then
        if [[ -d "$child" ]]; then
          # 递归合并
          mkdir -p "$dest"
          cp -R "$child"/. "$dest"/ 2>/dev/null || cp -a "$child"/. "$dest"/
          rm -rf "$child"
        else
          cp -f "$child" "$dest"
          rm -f "$child"
        fi
      else
        mv "$child" "$dest"
      fi
    done
    shopt -u nullglob dotglob
    rmdir "$oldDir" 2>/dev/null || rm -rf "$oldDir"
  else
    # 只建父目录：若先建好 $newDir，mv 会变成"移入"而非"重命名"，凭空多一层 mpmt
    mkdir -p "${newDir%/*}"
    mv "$oldDir" "$newDir"
  fi
  # 清理空的父目录（最多 4 层，至源码根）
  parent="${oldDir%/*}"
  for _ in 1 2 3 4; do
    if [[ "$parent" == "$javaRoot" ]] || [[ ! -d "$parent" ]]; then break; fi
    if rmdir "$parent" 2>/dev/null; then
      parent="${parent%/*}"
    else
      break
    fi
  done
done

echo ""
info "done: text_files=$changed_files, renamed_files=$renamed_files, package_moves=$moved_packages"
if [[ "$DRY_RUN" == "true" ]]; then
  warn "dry-run 未写盘。去掉 --dry-run 重新执行以写盘。"
  echo ""
  echo "预览命令："
  echo "  ./init.sh --id $NEW_ID --group $NEW_GROUP --name \"$NEW_NAME\" $( [[ "$REWRITE_CHANNELS" == "true" ]] && echo "--rewrite-channels" )"
else
  ok "已完成。建议执行冒烟："
  echo "  ./gradlew --no-daemon :core:domain:compileJava :platform:bukkit:common:compileJava"
  echo ""
  echo "或全量（较慢）："
  echo "  ./gradlew :buildAll"
  echo ""
  info "init.sh 已完成使命，可删除：rm init.sh"
fi
