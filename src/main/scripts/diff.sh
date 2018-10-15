#!/bin/bash
#
# Outputs report items that are in the first directory but
# not the second directory.
#

if [ "$#" -ne 2 ]; then
    (>&2 echo "Usage: $0 new_dir old_dir")
    exit 1
fi
new_dir=$1
old_dir=$2

new_files=`find $new_dir -name '*.txt' | grep -v '_'`
for new_file in $new_files; do
    old_file=`echo $new_file | sed "s,$new_dir,$old_dir,"`
    diff_file=`echo $new_file | sed "s/\.txt/_diff.txt/"`
    if [ -e "$old_file" ]; then
        new_hdg=`head -1 $new_file`
        old_hdg=`head -1 $old_file`
        if [ "$new_hdg" == "$old_hdg" ]; then
            diffs=`comm -23 <(sort $new_file) <(sort $old_file)`
            if [ -n "$diffs" ]; then
                # Don't echo the above variables, since that
                # replaces tabs with spaces.
                head -1 $new_file >$diff_file
                comm -23 <(sort $new_file) <(sort $old_file) >>$diff_file
            fi
        else
            cp "$new_file" "$diff_file"
        fi
    else
        cp "$new_file" "$diff_file"
    fi
done
