#!/usr/bin/env python3
"""
Jammer Pre-Flight Check
Roda no Termux (ou qualquer lugar com Python 3) pra pegar erros óbvios
ANTES de dar push pro GitHub.
"""

import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(".").resolve()
APP_ROOT = REPO_ROOT / "app"
SRC_ROOT = APP_ROOT / "src/main/java/android/kimyona/jammer"
RES_ROOT = APP_ROOT / "src/main/res"
BUILD_GRADLE = APP_ROOT / "build.gradle.kts"
MANIFEST = APP_ROOT / "src/main/AndroidManifest.xml"

errors = []
warnings = []

def error(msg):
    errors.append(f"❌ {msg}")

def warn(msg):
    warnings.append(f"⚠️  {msg}")

def info(msg):
    print(f"✅ {msg}")

# =============================================================================
# 1. Coleta todos os arquivos Kotlin e seus pacotes
# =============================================================================
kt_files = list(SRC_ROOT.rglob("*.kt")) if SRC_ROOT.exists() else []
kt_by_package = {}      
kt_classes = {}         
kt_packages = set()

for f in kt_files:
    content = f.read_text(encoding="utf-8", errors="ignore")
    m = re.search(r'^package\s+([\w.]+)', content, re.MULTILINE)
    if m:
        pkg = m.group(1)
        kt_by_package.setdefault(pkg, []).append(f)
        kt_packages.add(pkg)
        # CORRIGIDO: Uso correto do \b como raw string para evitar escape inválido
        for decl in re.finditer(r'\b(class|object|interface|enum class)\s+(\w+)', content):
            class_name = decl.group(2)
            kt_classes[class_name] = pkg

# =============================================================================
# 2. Checa imports internos
# =============================================================================
print("🔍 Verificando imports internos...")
for f in kt_files:
    content = f.read_text(encoding="utf-8", errors="ignore")
    imports = re.findall(r'^import\s+([\w.]+)', content, re.MULTILINE)
    for imp in imports:
        if imp.startswith("android.kimyona.jammer.") and not imp.startswith("android.kimyona.jammer.R"):
            parts = imp.split(".")
            class_name = parts[-1]
            pkg = ".".join(parts[:-1])
            if class_name[0].isupper():
                if class_name not in kt_classes:
                    error(f"{f.name}: import '{imp}' → classe '{class_name}' NÃO ENCONTRADA no projeto")
                elif kt_classes[class_name] != pkg:
                    warn(f"{f.name}: import '{imp}' → classe '{class_name}' está em '{kt_classes[class_name]}', não em '{pkg}'")

if not any("import" in e for e in errors):
    info("Todos os imports internos estão OK")

# =============================================================================
# 3. Checa dependências declaradas no build.gradle.kts
# =============================================================================
print("🔍 Verificando dependências do Gradle...")
gradle_deps = []
if BUILD_GRADLE.exists():
    gradle_text = BUILD_GRADLE.read_text(encoding="utf-8", errors="ignore")
    gradle_deps = re.findall(r'implementation\("([^"]+)"\)', gradle_text)

EXTERNAL_IMPORTS = {
    "io.github.jamaismagic.ffmpeg.FFmpegKit": "io.github.jamaismagic.ffmpeg:ffmpeg-kit",
    "io.github.jamaismagic.ffmpeg.FFprobeKit": "io.github.jamaismagic.ffmpeg:ffmpeg-kit",
    "io.github.jamaismagic.ffmpeg.ReturnCode": "io.github.jamaismagic.ffmpeg:ffmpeg-kit",
    "androidx.media3.exoplayer.ExoPlayer": "androidx.media3:media3-exoplayer",
    "androidx.media3.session.MediaSession": "androidx.media3:media3-session",
    "androidx.recyclerview.widget.RecyclerView": "androidx.recyclerview:recyclerview",
    "androidx.lifecycle.lifecycleScope": "androidx.lifecycle:lifecycle-runtime-ktx",
    "androidx.core.app.ActivityCompat": "androidx.core:core-ktx",
    "com.google.android.material": "com.google.android.material:material",
    "retrofit2.Retrofit": "com.squareup.retrofit2:retrofit",
    "retrofit2.converter.gson.GsonConverterFactory": "com.squareup.retrofit2:converter-gson",
    "okhttp3.OkHttpClient": "com.squareup.okhttp3:okhttp",
    "com.google.gson.Gson": "com.google.code.gson:gson",
    "kotlinx.coroutines.launch": "org.jetbrains.kotlinx:kotlinx-coroutines-android",
    "dev.rikka.shizuku.Shizuku": "dev.rikka.shizuku:api",
}

for f in kt_files:
    content = f.read_text(encoding="utf-8", errors="ignore")
    imports = re.findall(r'^import\s+([\w.]+)', content, re.MULTILINE)
    for imp in imports:
        for prefix, artifact in EXTERNAL_IMPORTS.items():
            if imp.startswith(prefix):
                found = any(artifact in dep for dep in gradle_deps)
                if not found:
                    error(f"{f.name}: import '{imp}' precisa da dependência '{artifact}' em app/build.gradle.kts")

if not any("dependência" in e for e in errors):
    info("Dependências externas parecem OK")

# =============================================================================
# 4. Checa recursos Android (R.id, R.layout, R.string, R.color, R.drawable)
# =============================================================================
print("🔍 Verificando recursos Android (R.*)...")

resources = {
    "id": set(),
    "layout": set(),
    "string": set(),
    "color": set(),
    "drawable": set(),
}

if RES_ROOT.exists():
    # IDs e nomes de layouts
    for layout_file in (RES_ROOT / "layout").glob("*.xml"):
        resources["layout"].add(layout_file.stem)
        xml = layout_file.read_text(encoding="utf-8", errors="ignore")
        for m in re.finditer(r'android:id="@\+id/(\w+)"', xml):
            resources["id"].add(m.group(1))

    # CORRIGIDO: Mapeia arquivos da pasta drawable (revisando subpastas como drawable-v24, etc)
    for drawable_folder in RES_ROOT.glob("drawable*"):
        if drawable_folder.is_dir():
            for img_file in drawable_folder.glob("*"):
                if img_file.is_file() and not img_file.name.startswith("."):
                    resources["drawable"].add(img_file.stem)

    # Strings
    strings_file = RES_ROOT / "values/strings.xml"
    if strings_file.exists():
        xml = strings_file.read_text(encoding="utf-8", errors="ignore")
        for m in re.finditer(r'<string name="(\w+)"', xml):
            resources["string"].add(m.group(1))

    # Colors
    colors_file = RES_ROOT / "values/colors.xml"
    if colors_file.exists():
        xml = colors_file.read_text(encoding="utf-8", errors="ignore")
        for m in re.finditer(r'<color name="(\w+)"', xml):
            resources["color"].add(m.group(1))

# Verifica referências no código Kotlin
for f in kt_files:
    content = f.read_text(encoding="utf-8", errors="ignore")
    for m in re.finditer(r'R\.(id|layout|string|color|drawable)\.(\w+)', content):
        rtype, rname = m.group(1), m.group(2)
        if rname not in resources.get(rtype, set()):
            error(f"{f.name}: recurso 'R.{rtype}.{rname}' referenciado mas NÃO ENCONTRADO em res/")

if not any("recurso" in e for e in errors):
    info("Recursos Android (R.*) parecem OK")

# =============================================================================
# 5. Checa AndroidManifest.xml
# =============================================================================
print("🔍 Verificando AndroidManifest.xml...")
if MANIFEST.exists():
    manifest_text = MANIFEST.read_text(encoding="utf-8", errors="ignore")
    if 'package="android.kimyona.jammer"' in manifest_text:
        warn("AndroidManifest.xml ainda tem package=\"android.kimyona.jammer\". Remova — o namespace agora vai no build.gradle.kts")
else:
    error("AndroidManifest.xml não encontrado!")

# =============================================================================
# 6. Checa se MainActivity.kt existe
# =============================================================================
print("🔍 Verificando estrutura básica...")
main_activity = SRC_ROOT / "MainActivity.kt"
if not main_activity.exists():
    error("MainActivity.kt não encontrado!")
else:
    info("MainActivity.kt encontrado")

# =============================================================================
# 7. Resumo
# =============================================================================
print()
print("=" * 60)
if errors:
    print(f"❌ ERROS ENCONTRADOS: {len(errors)}")
    for e in errors:
        print(f"   {e}")
else:
    print("✅ NENHUM ERRO CRÍTICO ENCONTRADO!")

if warnings:
    print(f"\n⚠️  AVISOS: {len(warnings)}")
    for w in warnings:
        print(f"   {w}")

print("=" * 60)

if errors:
    sys.exit(1)
else:
    print("\n🚀 Pode dar push! (mas lembre: isso não substitui a compilação real)")
    sys.exit(0)
