#!/usr/bin/env python3
"""Dump the static game tables embedded in HUGOWIN1.EXE (v1.5) to JSON.

The game logic is not in the .DAT files: vocabulary, objects, hotspots, background
commands, action lists etc. are initialised C structs in the EXE's data segments
(ScummVM ships the same data, pre-extracted, as hugo.dat).

Usage: exe_tables.py HUGOWIN1.EXE out.json [STRINGS.DAT]

With STRINGS.DAT given, every STRINGS.DAT index in the output also gets its decoded text.
If hugo1w_names.json (next to this script) exists, action lists, actions and objects get
the symbolic names used by ScummVM; each name is only applied after checking that the
action types and timers in that list match exactly.

Segment file offsets are for this exact build (250832 bytes); they are derived from
the NE segment table so a different build will fail loudly rather than silently.
"""
import json, struct, sys

NUM_OBJECTS, OBJ_SIZE, NUM_SCREENS = 88, 0x54, 17
NUM_PLAY_SCREENS = 16                  # per-screen tables (actions, background) have 16 entries;
                                       # screen 16 is a second copy of the House picture


def ne_segments(exe):
    ne = struct.unpack_from('<I', exe, 0x3c)[0]
    nseg, segoff, align = struct.unpack_from('<H', exe, ne + 0x1c)[0], struct.unpack_from('<H', exe, ne + 0x22)[0], \
        struct.unpack_from('<H', exe, ne + 0x32)[0]
    segs = []
    for i in range(nseg):
        so, sl, sf, sm = struct.unpack_from('<4H', exe, ne + segoff + 8 * i)
        segs.append((so << align, sl or (0x10000 if so else 0)))
    return segs


class Tables:
    def __init__(self, exe):
        segs = ne_segments(exe)
        o, l = segs[14]                    # segment 15 = DGROUP (1070)
        self.dg = exe[o:o + l]
        o, l = segs[4]                     # segment 5 = object table (1020)
        self.objseg = exe[o:o + l]
        if len(self.objseg) < NUM_OBJECTS * OBJ_SIZE:
            raise SystemExit('unexpected segment layout - not v1.5?')

    def w(self, o): return struct.unpack_from('<H', self.dg, o)[0]
    def sw(self, o): return struct.unpack_from('<h', self.dg, o)[0]

    def s(self, o):
        if not o: return None
        return self.dg[o:self.dg.index(b'\0', o)].decode('cp1252')

    def wordlist(self, o):
        """char *list[] terminated by an empty string."""
        out = []
        while o and self.s(self.w(o)):
            out.append(self.s(self.w(o))); o += 2
        return out

    def ptr_table(self, base, conv):
        out, i = [], 0
        while self.w(base + 2 * i):
            out.append(conv(self.w(base + 2 * i))); i += 1
        return out

    def nouns(self): return self.ptr_table(0x3b4, self.wordlist)
    def verbs(self): return self.ptr_table(0x57c, self.wordlist)
    def screen_names(self): return [self.s(self.w(0x2ca2 + 2 * i)) for i in range(NUM_SCREENS)]

    def hotspots(self):
        out, i = [], 0
        while True:
            v = struct.unpack_from('<9h', self.dg, 0x34d2 + 18 * i)
            if v[0] < 0: return out
            out.append(dict(zip(('screen', 'x1', 'y1', 'x2', 'y2', 'act_ptr', 'viewx', 'viewy', 'direction'), v)))
            i += 1

    def points(self):
        n = self.w(0x36fe)
        return [self.dg[0x36ca + 4 * i] for i in range(n)]

    def background_list(self, ptr):
        """struct background {char **verb; char **noun; int commentIndex; bool matchFl; int8 roomState; u8 bonusIndex}"""
        out, i = [], 0
        while ptr and self.w(self.w(ptr + 10 * i)):
            b = ptr + 10 * i
            out.append(dict(verb=self.wordlist(self.w(b))[:1], noun=self.wordlist(self.w(b + 2))[:1],
                            string=self.w(b + 4), match=self.w(b + 6), room_state=struct.unpack_from('<b', self.dg, b + 8)[0],
                            bonus=self.dg[b + 9]))
            i += 1
        return out

    def objects(self):
        out = []
        for k in range(NUM_OBJECTS):
            r = self.objseg[k * OBJ_SIZE:(k + 1) * OBJ_SIZE]
            g = lambda fmt, off: struct.unpack_from(fmt, r, off)[0]
            out.append(dict(
                index=k, noun=self.wordlist(g('<H', 0)), data_ptr=g('<H', 2), path=g('<h', 4),
                vx_path=g('<h', 6), vy_path=g('<h', 8), act_ptr=g('<H', 0x0a), seq_count=r[0x0c],
                seq_images=[g('<H', 0x12 + 6 * j) for j in range(4)], cycling=g('<h', 0x2a),
                cycle_count=r[0x2c], frame_interval=r[0x2d], radius=g('<b', 0x2f), screen=r[0x30],
                x=g('<h', 0x32), y=g('<h', 0x34), vx=g('<b', 0x3a), vy=g('<b', 0x3b), value=r[0x3c],
                generic_cmd=g('<H', 0x3e), cmd_ptr=g('<H', 0x40), carried=g('<h', 0x42), state=r[0x44],
                verb_only=g('<h', 0x46), priority=g('<h', 0x48), viewx=g('<h', 0x4a), viewy=g('<h', 0x4c),
                direction=r[0x4e]))
        return out

    # ------------------------------------------------------------------ scripts
    # Every action struct starts {u16 type; i16 timer}. Fields below are (name, offset, kind),
    # read from the DoAction switch (1010:55a6); all fields are word-aligned except where two
    # int8s share a word (offsets 8/9).  kinds: w=i16, u=u16, b=u8, sb=i8, obj=object index,
    # list=action-list ptr, str=STRINGS.DAT index, noun=word-list ptr, resp=ptr to str-index list,
    # dword=u32, atype=action type.
    ACTIONS = {
        0: ('ASCHEDULE', [('list', 4, 'list')]),
        1: ('START_OBJ', [('obj', 4, 'obj'), ('cycle_count', 6, 'b'), ('cycle', 8, 'w')]),
        2: ('INIT_OBJXY', [('obj', 4, 'obj'), ('x', 6, 'w'), ('y', 8, 'w')]),
        3: ('PROMPT', [('prompt', 4, 'str'), ('responses', 6, 'resp'), ('pass', 8, 'list'), ('fail', 10, 'list')]),
        4: ('BKGD_COLOR', [('color', 4, 'dword')]),
        5: ('INIT_OBJVXY', [('obj', 4, 'obj'), ('vx', 6, 'sb'), ('vy', 8, 'sb')]),
        6: ('INIT_CARRY', [('obj', 4, 'obj'), ('carried', 6, 'w')]),
        7: ('INIT_HF_COORD', [('obj', 4, 'obj')]),
        8: ('NEW_SCREEN', [('screen', 4, 'w')]),
        9: ('INIT_OBJSTATE', [('obj', 4, 'obj'), ('state', 6, 'b')]),
        10: ('INIT_PATH', [('obj', 4, 'obj'), ('path', 6, 'w'), ('vx_path', 8, 'sb'), ('vy_path', 9, 'sb')]),
        11: ('COND_R', [('obj', 4, 'obj'), ('state', 6, 'b'), ('pass', 8, 'list'), ('fail', 10, 'list')]),
        12: ('TEXT', [('text', 4, 'str')]),
        13: ('SWAP_IMAGES', [('obj1', 4, 'obj'), ('obj2', 6, 'obj')]),
        14: ('COND_SCR', [('obj', 4, 'obj'), ('screen', 6, 'w'), ('pass', 8, 'list'), ('fail', 10, 'list')]),
        15: ('AUTOPILOT', [('obj1', 4, 'obj'), ('obj2', 6, 'obj'), ('dx', 8, 'sb'), ('dy', 9, 'sb')]),
        16: ('INIT_OBJ_SEQ', [('obj', 4, 'obj'), ('seq', 6, 'w')]),
        17: ('SET_STATE_BITS', [('obj', 4, 'obj'), ('mask', 6, 'b')]),
        18: ('CLEAR_STATE_BITS', [('obj', 4, 'obj'), ('mask', 6, 'b')]),
        19: ('TEST_STATE_BITS', [('obj', 4, 'obj'), ('mask', 6, 'w'), ('pass', 8, 'list'), ('fail', 10, 'list')]),
        20: ('DEL_EVENTS', [('action_type', 4, 'atype')]),
        21: ('GAMEOVER', []),
        22: ('INIT_HH_COORD', [('obj', 4, 'obj')]),
        23: ('EXIT', []),
        24: ('BONUS', [('point', 4, 'w')]),
        25: ('COND_BOX', [('obj', 4, 'obj'), ('x1', 6, 'w'), ('y1', 8, 'w'), ('x2', 10, 'w'), ('y2', 12, 'w'),
                          ('pass', 14, 'list'), ('fail', 16, 'list')]),
        26: ('SOUND', [('sound', 4, 'w')]),
        27: ('ADD_SCORE', [('obj', 4, 'obj')]),
        28: ('SUB_SCORE', [('obj', 4, 'obj')]),
        29: ('COND_CARRY', [('obj', 4, 'obj'), ('pass', 6, 'list'), ('fail', 8, 'list')]),
        30: ('INIT_MAZE', [('size', 4, 'b'), ('x1', 6, 'w'), ('y1', 8, 'w'), ('x2', 10, 'w'), ('y2', 12, 'w'),
                           ('x3', 14, 'w'), ('x4', 16, 'w'), ('first_screen', 18, 'b')]),
        31: ('EXIT_MAZE', []),
        32: ('INIT_PRIORITY', [('obj', 4, 'obj'), ('priority', 6, 'w')]),
        33: ('INIT_SCREEN', [('obj', 4, 'obj'), ('screen', 6, 'b')]),
        34: ('AGSCHEDULE', [('list', 4, 'list')]),
        35: ('REMAPPAL', [('old_color', 4, 'w'), ('new_color', 6, 'w')]),
        36: ('COND_NOUN', [('noun', 4, 'noun'), ('pass', 6, 'list'), ('fail', 8, 'list')]),
        37: ('SCREEN_STATE', [('screen', 4, 'w'), ('state', 6, 'b')]),
        38: ('INIT_LIPS', [('lips_obj', 4, 'obj'), ('obj', 6, 'obj'), ('dx', 8, 'b'), ('dy', 9, 'b')]),
        39: ('INIT_STORY_MODE', [('story_mode', 4, 'w')]),
        40: ('WARN', [('text', 4, 'str')]),
        41: ('COND_BONUS', [('point', 4, 'w'), ('pass', 6, 'list'), ('fail', 8, 'list')]),
        42: ('TEXT_TAKE', [('obj', 4, 'obj')]),
        43: ('YESNO', [('prompt', 4, 'str'), ('yes', 6, 'list'), ('no', 8, 'list')]),
        44: ('STOP_ROUTE', []),
        45: ('COND_ROUTE', [('route_index', 4, 'w'), ('pass', 6, 'list'), ('fail', 8, 'list')]),
        46: ('INIT_JUMPEXIT', [('jump_exit', 4, 'w')]),
        47: ('INIT_VIEW', [('obj', 4, 'obj'), ('viewx', 6, 'w'), ('viewy', 8, 'w'), ('direction', 10, 'b')]),
        48: ('INIT_OBJ_FRAME', [('obj', 4, 'obj'), ('seq', 6, 'w'), ('frame', 8, 'w')]),
        255: ('ANULL', []),
    }
    # Semantics of the two branches, as implemented in DoAction:
    BRANCH_NOTES = {
        'COND_R': 'pass if objects[obj].state == state',
        'COND_SCR': 'pass if objects[obj].screen == screen',
        'TEST_STATE_BITS': 'pass if (objects[obj].state & mask) == mask',
        'COND_BOX': 'pass if hero-relative foot point of obj is inside (x1,y1)-(x2,y2)',
        'COND_CARRY': 'pass if objects[obj].carried',
        'COND_NOUN': 'pass if a word of the noun list is in the typed line',
        'COND_BONUS': 'pass if points[point] already scored',
        'YESNO': 'yes/no box',
        'COND_ROUTE': 'pass if current route index >= route_index',
        'PROMPT': 'pass if the typed answer contains one of the response strings',
    }

    def hexp(self, p): return '0x%04x' % p

    def valid_ptr(self, p): return 0 < p < len(self.dg)

    def _field(self, base, off, kind):
        g = lambda f: struct.unpack_from(f, self.dg, base + off)[0]
        if kind in ('w', 'obj'): return g('<h')
        if kind in ('u', 'atype'): return g('<H')
        if kind == 'b': return g('<B')
        if kind == 'sb': return g('<b')
        if kind == 'dword': return g('<I')
        if kind == 'str': return g('<H')
        if kind in ('list', 'noun', 'resp'): return g('<H')
        raise ValueError(kind)

    def decode_scripts(self, roots):
        """Walk every action list reachable from `roots` (list pointers). Returns
        (lists, actions, responses) dicts keyed by hex address."""
        lists, actions, resp = {}, {}, {}
        todo = [r for r in roots if r]
        while todo:
            lp = todo.pop()
            if self.hexp(lp) in lists:
                continue
            if not self.valid_ptr(lp):
                raise ValueError('bad action list pointer %#x' % lp)
            items, i = [], 0
            while self.w(lp + 2 * i):
                items.append(self.w(lp + 2 * i)); i += 1
            lists[self.hexp(lp)] = [self.hexp(a) for a in items]
            for a in items:
                if self.hexp(a) in actions:
                    continue
                typ, timer = self.w(a), self.sw(a + 2)
                if typ not in self.ACTIONS:
                    raise ValueError('unknown action type %d at %#x (list %#x)' % (typ, a, lp))
                name, fields = self.ACTIONS[typ]
                act = dict(type=typ, name=name, timer=timer)
                for fname, off, kind in fields:
                    v = self._field(a, off, kind)
                    if kind == 'list':
                        act[fname] = self.hexp(v) if v else None
                        if v: todo.append(v)
                    elif kind == 'noun':
                        act[fname] = self.wordlist(v)[:1] and self.wordlist(v)[0]
                    elif kind == 'resp':
                        r, k = [], 0
                        while self.sw(v + 2 * k) != -1:
                            r.append(self.w(v + 2 * k)); k += 1
                        act[fname] = r
                    elif kind == 'atype':
                        act[fname] = self.ACTIONS.get(v, ('?%d' % v,))[0]
                    else:
                        act[fname] = v
                if name in self.BRANCH_NOTES:
                    act['branch'] = self.BRANCH_NOTES[name]
                actions[self.hexp(a)] = act
        return lists, actions

    def cmds(self, ptr):
        """Object verb commands: ptr -> 0-terminated list of ptrs to
        struct cmd {char **verb; char ***reqObjs; char *noCarry; u8 reqState; u8 newState;
                    char *wrongState; char *done; u16 actList}  (14 bytes).
        reqState 0xFF = don't care. Texts are near pointers to C strings (not STRINGS.DAT)."""
        out, i = [], 0
        while ptr and self.w(ptr + 2 * i):
            c = self.w(ptr + 2 * i)
            verb, req, nocarry, rs, ns, wrong, done, act = struct.unpack_from('<3H2B3H', self.dg, c)
            reqs, k = [], 0
            while req and self.w(req + 2 * k):
                reqs.append(self.wordlist(self.w(req + 2 * k))[0]); k += 1
            out.append(dict(verb=self.wordlist(verb)[0], requires=reqs, text_not_carried=self.s(nocarry),
                            req_state=None if rs == 0xFF else rs, new_state=ns,
                            text_wrong_state=self.s(wrong), text_done=self.s(done),
                            actions=self.hexp(act) if act else None))
            i += 1
        return out

    def uses(self):
        """struct uses {i16 obj; char *noTarget; {char **noun; char **verb} target[12]} (52 bytes),
        stored in segment 5 right after the object table (1020:1CE0), ends with obj == 88.
        Read by UseObject 1010:4f12. Pointers are DGROUP near pointers."""
        out, p = [], NUM_OBJECTS * OBJ_SIZE
        sw = lambda o: struct.unpack_from('<h', self.objseg, o)[0]
        uw = lambda o: struct.unpack_from('<H', self.objseg, o)[0]
        while sw(p) != NUM_OBJECTS:
            tg, q = [], p + 4
            while q < p + 52 and uw(q):
                tg.append(dict(noun=self.wordlist(uw(q))[0], verb=self.wordlist(uw(q + 2))[0])); q += 4
            out.append(dict(obj=sw(p), text_no_target=self.s(uw(p + 2)), targets=tg))
            p += 52
        return out

    def screen_actions(self):
        """DGROUP:2D08 -> per screen (16), 0-terminated list of action-list ptrs (run on entry)."""
        out = []
        for s in range(NUM_PLAY_SCREENS):
            p, lst, i = self.w(0x2d08 + 2 * s), [], 0
            while p and self.w(p + 2 * i):
                lst.append(self.w(p + 2 * i)); i += 1
            out.append(lst)
        return out

    def object_description(self, ob):
        p, g = ob['data_ptr'], ob['generic_cmd']
        if not p: return None
        if g & 8:                               # state-indexed char*[]
            out, i = [], 0
            while i < 8 and self.valid_ptr(self.w(p + 2 * i)):
                s = self.s(self.w(p + 2 * i))
                if not s or not all(32 <= ord(c) < 256 or c in '\n\r' for c in s): break
                out.append(s); i += 1
            return out
        return self.s(p)

    def dump(self):
        objs = self.objects()
        for ob in objs:
            ob['description'] = self.object_description(ob)
            ob['cmds'] = self.cmds(ob['cmd_ptr'])
        hs, sa = self.hotspots(), self.screen_actions()
        roots = [o['act_ptr'] for o in objs] + [h['act_ptr'] for h in hs] + [p for s in sa for p in s]
        roots += [int(c['actions'], 16) for o in objs for c in o['cmds'] if c['actions']]
        lists, actions = self.decode_scripts(roots)
        for o in objs:
            o['act_ptr'] = self.hexp(o['act_ptr']) if o['act_ptr'] else None
        for h in hs:
            h['act_ptr'] = self.hexp(h['act_ptr']) if h['act_ptr'] else None
        return dict(
            screen_names=self.screen_names(), nouns=self.nouns(), verbs=self.verbs(),
            hotspots=hs, points=self.points(),
            catchall_background=self.background_list(0x2d28),
            screen_background=[self.background_list(self.w(0x34b2 + 2 * i)) for i in range(NUM_PLAY_SCREENS)],
            screen_actions=[[self.hexp(p) for p in s] for s in sa],
            uses=self.uses(), objects=objs, action_lists=lists, actions=actions)


def apply_names(data, names_path):
    """Attach ScummVM identifiers. Lists are matched in address order (the original source
    declares them in the same order as ScummVM's actListArr_1w) and verified by signature."""
    n = json.load(open(names_path))
    order = sorted(data['action_lists'], key=lambda a: int(a, 16))
    if len(order) != len(n['action_lists']):
        print('names: list count differs (%d vs %d), not applied' % (len(order), len(n['action_lists'])))
        return 0
    def same(a, b):  # timer None = expression the name parser could not evaluate
        return a[0] == b[0] and (b[1] is None or a[1] == b[1])
    applied, list_names = 0, {}
    for addr, ref in zip(order, n['action_lists']):
        acts = data['action_lists'][addr]
        sig = [(data['actions'][a]['name'], data['actions'][a]['timer']) for a in acts]
        if len(sig) != len(ref['sig']) or not all(same(x, y) for x, y in zip(sig, ref['sig'])):
            print('names: signature mismatch at list %s (%s), skipped' % (addr, ref['name']))
            continue
        list_names[addr] = ref['name']
        for a, an in zip(acts, ref['actions']):
            data['actions'][a].setdefault('id', an)
        applied += 1
    data['action_list_names'] = list_names
    for o in data['objects']:
        if o['index'] < len(n['objects']):
            o['id'] = n['objects'][o['index']]
    return applied


def add_string_text(data, strings):
    for a in data['actions'].values():
        name, fields = Tables.ACTIONS[a['type']]
        for fname, off, kind in fields:
            if kind == 'str' and 0 <= a[fname] < len(strings):
                a[fname + '_text'] = strings[a[fname]]
            if kind == 'resp':
                a[fname + '_text'] = [strings[i] for i in a[fname] if 0 <= i < len(strings)]
    for scr in [data['catchall_background']] + data['screen_background']:
        for b in scr:
            if 0 <= b['string'] < len(strings):
                b['text'] = strings[b['string']]


if __name__ == '__main__':
    import os
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    t = Tables(open(sys.argv[1], 'rb').read())
    data = t.dump()
    here = os.path.dirname(os.path.abspath(__file__))
    if len(sys.argv) == 4:
        sys.path.insert(0, here)
        import hugo_dat
        add_string_text(data, hugo_dat.read_strings(sys.argv[3]))
    names = os.path.join(here, 'hugo1w_names.json')
    named = apply_names(data, names) if os.path.exists(names) else 0
    json.dump(data, open(sys.argv[2], 'w'), indent=1, ensure_ascii=False)
    print('%d nouns, %d verbs, %d objects, %d hotspots, %d action lists (%d named), %d actions -> %s' %
          (len(data['nouns']), len(data['verbs']), len(data['objects']), len(data['hotspots']),
           len(data['action_lists']), named, len(data['actions']), sys.argv[2]))
