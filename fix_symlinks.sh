#!/bin/bash
cd app/src/main/jni/hev-socks5-tunnel/third-part/hev-task-system/include
for file in *.h; do
    if [ -f "$file" ] && [ $(wc -l < "$file" 2>/dev/null) -eq 1 ]; then
        target=$(cat "$file" | grep "^\.\./")
        if [ -n "$target" ]; then
            src_file="../${target#../}"
            if [ -f "$src_file" ]; then
                echo "Fixing $file -> $src_file"
                cp "$src_file" "$file"
            fi
        fi
    fi
done
