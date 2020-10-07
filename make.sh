#!/usr/bin/env bash

PROJECT=YUBOXFrameworkAssemble

if [[ -z "$INSTALLDIR" ]]; then
    #INSTALLDIR="$HOME/Documents/Arduino"
    INSTALLDIR="$HOME/Arduino"
fi
echo "INSTALLDIR: $INSTALLDIR"

# TODO: reemplazar rutas quemadas
basepath=$HOME/checkouts/arduino/arduino-1.8.13/lib
pde_path=$basepath/pde.jar
core_path=$basepath/arduino-core.jar
lib_path=$basepath/commons-compress-1.8.jar

if [[ -z "$core_path" || -z "$pde_path" ]]; then
    echo "Some java libraries have not been built yet (did you run ant build?)"
    return 1
fi
echo "pde_path: $pde_path"
echo "core_path: $core_path"
echo "lib_path: $lib_path"

set -e

mkdir -p bin
javac -target 1.8 -cp "$pde_path:$core_path:$lib_path" \
      -d bin `find src/ -name *.java` `find lib/ -name *.java`

pushd bin
mkdir -p $INSTALLDIR/tools
rm -rf $INSTALLDIR/tools/$PROJECT
mkdir -p $INSTALLDIR/tools/$PROJECT/tool
zip -r $INSTALLDIR/tools/$PROJECT/tool/$PROJECT.jar *
popd

dist=$PWD/dist
rev=$(git describe --tags)
mkdir -p $dist
pushd $INSTALLDIR/tools
zip -r $dist/$PROJECT-$rev.zip $PROJECT/
popd

