for dir in ./*/
do
  mkdir -p "${dir}src/main/kotlin/com/stormunblessed/"
  cp ./Extractors.kt "${dir}src/main/kotlin/com/stormunblessed/"
done
