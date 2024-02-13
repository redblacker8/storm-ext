#!/bin/bash
for dir in ./*/; do
  if [ -d "$dir" ]; then
    if [ -f "$dir/build.gradle.kts" ]; then
      version=$(grep -oP '(?<=version = )\d+' "$dir/build.gradle.kts")
      if [ -n "$version" ]; then
        new_version=$((version + 1))
        sed -i "s/version = $version/version = $new_version/g" "$dir/build.gradle.kts"
      fi
    fi
  fi
done
