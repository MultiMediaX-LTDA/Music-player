#!/bin/bash
# /compile script

# 1. Verifica se check.py está no .gitignore
if ! grep -q "^check.py$" .gitignore; then
    echo "check.py" >> .gitignore
    echo "Adicionado check.py ao .gitignore"
fi

# 2. Se check.py está no Git, tira do tracking
if git ls-files | grep -q "^check.py$"; then
    git rm --cached check.py
    git commit -m "Remove check.py do tracking (automático via /compile)"
fi

# 3. Roda check.py local
python check.py

# 4. Build, add, commit, push
git add .
git commit -m "Update Jammer"
git push