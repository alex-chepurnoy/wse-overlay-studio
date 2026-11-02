"""Labels test: every field the plugin page shows writes the setting its label says.

Checks ui/js/ovs-fields.js against sources the page does not control:
  - scope "object" and "overlay": the @JsonProperty names in the module's ObjectConfig.java and OverlayConfig.java;
    an "overlay" field must also be one a named overlay keeps (OVERLAY_KEYS in OverlayStore.java), or Save drops it
  - scope "templateProperty": the property names the module reads from a template (PROP_* in TemplateValidator.java)
  - scope "template": WSE's own template REST classes, where each @JsonProperty sits next to the @WMSXMLVariable
    xpath it reads and writes (TranscoderTemplateConfig.java for the template, TranscoderEncodeConfig.java for
    "encodes[]."); the field's shown key must be that xpath, and its rest name that JSON property
  - every field has a label and help text, labels are unique, and no text has an em-dash

--self-test plants known bugs in a copy of the registry and confirms each one is caught.

Usage: python -I tests/ui/labels_test.py [--self-test] <wse source root>

The WSE source root is the checkout holding WSE's own REST classes (for example C:/wowza/wse); it is not in this
repository, so it is required.
"""
import json
import os
import re
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FIELDS = os.path.join(REPO, 'ui', 'js', 'ovs-fields.js')
MODULE_REST = os.path.join(REPO, 'module', 'src', 'main', 'java', 'com', 'wowza', 'wms', 'plugin', 'overlaystudio', 'rest')
MODULE_TEMPLATES = os.path.join(os.path.dirname(MODULE_REST), 'templates')


def load_registry(path):
    """Runs the registry file in node with a window shim and returns its fields as JSON."""
    js = ("global.window={};require(%s);process.stdout.write(JSON.stringify(window.OverlayStudio.fields))" % json.dumps(path))
    out = subprocess.run(['node', '-e', js], capture_output=True, encoding='utf-8', check=True).stdout
    return json.loads(out)


def json_names(java):
    return set(re.findall(r'@JsonProperty\("([^"]+)"\)', open(java, encoding='utf-8').read()))


def rest_pairs(java):
    """(json name, xpath) for each field that has both annotations, in declaration order."""
    text = open(java, encoding='utf-8').read()
    pairs = {}
    for m in re.finditer(r'@JsonProperty\("([^"]+)"\)(.{0,400}?)@WMSXMLVariable\(xpath = "([^"]+)"\)', text, re.S):
        if '@JsonProperty' in m.group(2):
            continue
        pairs[m.group(1)] = m.group(3)
    return pairs


def string_list(java, name):
    """The string literals of a List.of(...) constant."""
    text = open(java, encoding='utf-8').read()
    m = re.search(name + r'\s*=\s*List\.of\(([^)]*)\)', text)
    return set(re.findall(r'"([^"]+)"', m.group(1))) if m else set()


def prop_names(java):
    return set(re.findall(r'PROP_[A-Z_]+\s*=\s*"([^"]+)"', open(java, encoding='utf-8').read()))


def short_xpath(xpath):
    p = re.sub(r'\[[^\]]*\]', '', xpath)
    p = p.replace('/Root/Transcode/', '').replace('/text()', '')
    return p.replace('Encodes/Encode/', 'Encode/')


def check(fields, wse):
    problems = []
    obj = json_names(os.path.join(MODULE_REST, 'ObjectConfig.java'))
    ovl = json_names(os.path.join(MODULE_REST, 'OverlayConfig.java'))
    kept = string_list(os.path.join(MODULE_TEMPLATES, 'OverlayStore.java'), 'OVERLAY_KEYS')
    props = prop_names(os.path.join(MODULE_TEMPLATES, 'TemplateValidator.java'))
    tdir = os.path.join(wse, 'wms', 'src', 'java', 'com', 'wowza', 'wms', 'rest', 'vhosts', 'transcoder')
    tpl = rest_pairs(os.path.join(tdir, 'TranscoderTemplateConfig.java'))
    enc = rest_pairs(os.path.join(tdir, 'TranscoderEncodeConfig.java'))
    labels = {}
    for name, f in fields.items():
        scope, key = f.get('scope'), f.get('key')
        if not f.get('label'):
            problems.append('%s: no label' % name)
        if 'help' not in f:
            problems.append('%s: no help text' % name)
        for k in ('label', 'help'):
            if '\u2014' in (f.get(k) or ''):
                problems.append('%s: em-dash in %s' % (name, k))
        labels.setdefault(f.get('label'), []).append(name)
        if scope == 'object':
            if key not in obj:
                problems.append('%s: object field %r is not in ObjectConfig' % (name, key))
        elif scope == 'overlay':
            if key not in ovl:
                problems.append('%s: overlay field %r is not in OverlayConfig' % (name, key))
            elif key not in kept:
                problems.append('%s: overlay field %r is not kept by a named overlay (OverlayStore.OVERLAY_KEYS)' % (name, key))
        elif scope == 'templateProperty':
            if key not in props:
                problems.append('%s: template property %r is not one the module reads' % (name, key))
        elif scope == 'template':
            rest = f.get('rest', '')
            table = enc if rest.startswith('encodes[].') else tpl
            jname = rest.replace('encodes[].', '')
            if jname not in table:
                problems.append('%s: WSE template REST has no %r' % (name, rest))
            elif short_xpath(table[jname]) != key:
                problems.append('%s: shown key %r but %r writes %r' % (name, key, rest, short_xpath(table[jname])))
            if f.get('type') == 'enum' and f.get('def') not in f.get('values', []):
                problems.append('%s: default %r is not among its values' % (name, f.get('def')))
        else:
            problems.append('%s: unknown scope %r' % (name, scope))
    for label, names in labels.items():
        if len(names) > 1:
            problems.append('label %r used by %s' % (label, ', '.join(names)))
    return problems


PLANTED = [
    ('object key misspelled', "key: 'keyTolerance'", "key: 'keyTol'"),
    ('overlay field given object scope', "scope: 'overlay', key: 'refreshRate'", "scope: 'object', key: 'refreshRate'"),
    ('template REST name wrong', "rest: 'encodes[].videoBitrate'", "rest: 'encodes[].bitrate'"),
    ('shown key not what REST writes', "key: 'Decode/Video/Deinterlace'", "key: 'Decode/Deinterlace'"),
    ('template field on the wrong level', "rest: 'implementationScale'", "rest: 'encodes[].implementationScale'"),
    ('duplicate label', "label: 'Encoder GPU'", "label: 'Decoder GPU'"),
    ('overlay field a named overlay drops', "scope: 'overlay', key: 'threadDrawing'", "scope: 'overlay', key: 'overlaySource'"),
    ('template property misspelled', "key: 'overlayStudioEncodes1'", "key: 'overlayStudioEncode1'"),
    ('em-dash in help', "help: '-1 lets WSE choose.' },\n    deinterlace", "help: '-1 \u2014 WSE chooses.' },\n    deinterlace"),
]


def self_test(wse):
    src = open(FIELDS, encoding='utf-8').read()
    ok = True
    for label, find, repl in PLANTED:
        if find not in src:
            print('STALE   %-40s planted text not found' % label)
            ok = False
            continue
        d = tempfile.mkdtemp()
        p = os.path.join(d, 'ovs-fields.js')
        open(p, 'w', encoding='utf-8').write(src.replace(find, repl, 1))
        caught = bool(check(load_registry(p), wse))
        print('%s  %s' % ('CAUGHT ' if caught else 'MISSED ', label))
        ok &= caught
    return ok


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not args:
        print(__doc__)
        return 2
    wse = args[0]
    if '--self-test' in sys.argv:
        clean = check(load_registry(FIELDS), wse)
        if clean:
            print('registry is not clean, fix it first:\n  ' + '\n  '.join(clean))
            return 1
        return 0 if self_test(wse) else 1
    fields = load_registry(FIELDS)
    problems = check(fields, wse)
    for p in problems:
        print('FAIL  ' + p)
    print('%d fields checked, %d problems' % (len(fields), len(problems)))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
