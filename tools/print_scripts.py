#!/usr/bin/env python3
"""Pretty-print the game scripts from exe_tables.json (written by exe_tables.py).

Usage: print_scripts.py exe_tables.json > scripts.txt

Every action list is printed once with its name, who triggers it, and each action
with object / screen / list names resolved and texts inlined.
"""
import json, sys
from collections import defaultdict

d = json.load(open(sys.argv[1], encoding='utf-8'))
L, A = d['action_lists'], d['actions']
lname = lambda p: '%s(%s)' % (d.get('action_list_names', {}).get(p, 'list'), p) if p else '-'
objs = d['objects']
oname = lambda i: (objs[i].get('id') or objs[i]['noun'][0]) if 0 <= i < len(objs) else str(i)
screens = d['screen_names']
CYCLE = ['INVISIBLE', 'NOT_CYCLING', 'CYCLE_FORWARD', 'CYCLE_BACKWARD']   # this EXE's values
sname = lambda i: screens[i] if 0 <= i < len(screens) else str(i)

refs = defaultdict(list)
for o in objs:
    if o['act_ptr']: refs[o['act_ptr']].append('collision with %s' % oname(o['index']))
    for c in o['cmds']:
        if c['actions']: refs[c['actions']].append('"%s %s"' % (c['verb'], o['noun'][0]))
for i, h in enumerate(d['hotspots']):
    if h['act_ptr']: refs[h['act_ptr']].append('exit hotspot %d on %s' % (i, sname(h['screen'])))
for s, lst in enumerate(d['screen_actions']):
    for p in lst: refs[p].append('entering %s' % sname(s))
for addr, a in A.items():
    for k in ('list', 'pass', 'fail', 'yes', 'no'):
        if a.get(k): refs[a[k]].append('%s.%s' % (a.get('id', addr), k))

def fmt(a):
    out = []
    for k, v in a.items():
        if k in ('type', 'name', 'timer', 'id', 'branch') or k.endswith('_text'): continue
        if k in ('obj', 'obj1', 'obj2', 'lips_obj'): v = oname(v)
        elif k in ('screen', 'first_screen'): v = sname(v)
        elif k == 'cycle' and 0 <= v < 4: v = CYCLE[v]
        elif k in ('list', 'pass', 'fail', 'yes', 'no'): v = lname(v)
        out.append('%s=%s' % (k, v))
    for k, v in a.items():
        if k.endswith('_text'):
            out.append('%s=%r' % (k[:-5], v if isinstance(v, list) else v.replace('\n', ' ')))
    return ' '.join(out)

for p in sorted(L, key=lambda x: int(x, 16)):
    print('%s' % lname(p))
    for r in refs.get(p, []) or ['(not referenced from tables; started by the engine)']:
        print('    <- %s' % r)
    for ap in L[p]:
        a = A[ap]
        print('    t+%-4d %-16s %s' % (a['timer'], a['name'], fmt(a)))
    print()
