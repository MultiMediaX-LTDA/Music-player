#!/bin/bash

PROJECT="/sdcard/Jammer"
SRC="$PROJECT/src"
BIN="$PROJECT/bin"
LIBS="$PROJECT/libs"

mkdir -p $BIN

echo "🚀 Compiling Jammer source files (Kotlin + Java)..."

# Executa o kotlinc apontando para a biblioteca que baixamos
# A flag -Dkotlin.colors.output=never resolve o bug do jansi no Termux
kotlinc $SRC/shell.kt $SRC/MainActivity.java \
        -Dkotlin.colors.output=never \
        -classpath $LIBS/shizuku-api.jar \
        -d $BIN/jammer.jar

if [ $? -eq 0 ]; then
    echo "✅ Success! Output generated at $BIN/jammer.jar"
else
    echo "❌ Compilation failed."
    exit 1
fi
