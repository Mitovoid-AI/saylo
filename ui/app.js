// Saylo UI. Talks to Python through window.pywebview.api; Python pushes live events via saylo.on().
const $ = s => document.querySelector(s), $$ = s => [...document.querySelectorAll(s)];
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));
let api, st, profile;
let listeners = [];
window.saylo = {on: ev => listeners.forEach(fn => fn(ev))};

// ---------------- boot ----------------
async function init(realApi) {
  api = realApi;
  st = await api.state();
  profile = st.profile;
  $$('[data-logo]').forEach(el => el.replaceWith($('#logo').content.cloneNode(true)));
  $$('[data-hotkey]').forEach(el => el.innerHTML = st.hotkey.map(k => `<span class="kbd">${esc(k)}</span>`).join(' + '));
  st.onboarded ? showHome() : showOnb(0);
}
window.addEventListener('pywebviewready', () => init(window.pywebview.api));

function toast(msg, bad = false) {
  const t = $('#toast');
  t.textContent = msg; t.classList.toggle('bad', bad); t.classList.add('show');
  clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.remove('show'), 3200);
}
const save = patch => { Object.assign(profile, patch); return api.save_profile(patch); };

// ---------------- onboarding ----------------
const screens = $$('.screen');
const stepNames = $$('.steps span');
let cur = 0, leaveHook = null;

function showOnb(i) {
  $('#home').classList.add('hidden'); $('#onb').classList.remove('hidden');
  listeners = [];
  go(i);
}

function go(i) {
  if (leaveHook) { leaveHook(); leaveHook = null; }
  cur = Math.max(0, Math.min(i, screens.length - 1));
  screens.forEach((s, k) => s.classList.toggle('on', k === cur));
  const step = +screens[cur].dataset.step;
  stepNames.forEach((n, k) => { n.classList.toggle('on', k === step); n.classList.toggle('done', k < step); });
  $('#prog').style.width = ((cur + 1) / (screens.length + 1) * 100) + '%';
  const enter = enterHooks[screens[cur].dataset.id];
  if (enter) leaveHook = enter() || null;
}
$$('.back').forEach(b => b.onclick = () => go(cur - 1));
$$('.cta.next').forEach(b => b.addEventListener('click', () => { if (!b.disabled) go(cur + 1); }));

// chips: restore from profile, save on change
$$('.chips').forEach(g => {
  const key = g.dataset.key, single = g.classList.contains('single');
  const val = c => c.dataset.v || c.textContent.trim();
  const sync = () => {
    const sel = $$(`.chips[data-key="${key}"] .chip.sel`).map(val);
    save({[key]: single ? (sel[0] || '') : sel});
    const need = $(`.cta[data-need="${key}"]`);
    if (need) need.disabled = !sel.length;
  };
  g.restore = () => {
    const v = profile[key];
    g.querySelectorAll('.chip').forEach(c => c.classList.toggle('sel', Array.isArray(v) ? v.includes(val(c)) : v === val(c)));
    const need = $(`.cta[data-need="${key}"]`);
    if (need) need.disabled = !g.querySelector('.sel');
  };
  g.addEventListener('click', e => {
    const c = e.target.closest('.chip'); if (!c) return;
    if (single) g.querySelectorAll('.chip').forEach(x => x !== c && x.classList.remove('sel'));
    c.classList.toggle('sel'); sync();
  });
});

// privacy toggles (onboarding + settings share data-pref)
$$('.toggle[data-pref]').forEach(t => t.onclick = () => {
  const on = !t.classList.contains('on');
  save({[t.dataset.pref]: on}); syncToggles();
});
const syncToggles = () => $$('.toggle[data-pref]').forEach(t => t.classList.toggle('on', !!profile[t.dataset.pref]));

const enterHooks = {
  signup() {
    $('#name').value = profile.name; $('#email').value = profile.email;
    let tick = 0, timer;
    $('#b1').style.width = '0'; $('#b2').style.width = '0';
    setTimeout(() => { $('#b1').style.width = '30%'; $('#b2').style.width = '100%'; }, 80);
    timer = setInterval(() => {
      tick += 1 / 40; const e = 1 - Math.pow(1 - Math.min(tick, 1), 3);
      $('#n1').textContent = Math.round(45 * e); $('#n2').textContent = Math.round(150 * e);
      if (tick >= 1) clearInterval(timer);
    }, 40);
    return () => clearInterval(timer);
  },
  role() { $('.chips[data-key="role"]').restore(); },
  places() { $('.chips[data-key="places"]').restore(); },
  tone() {
    $('.chips[data-key="source"]').restore();
    const pick = v => {
      $$('#tones .tone').forEach(t => t.classList.toggle('sel', t.dataset.v === v));
      $('#toneOut').value = $(`#tones .tone[data-v="${v}"] small`).textContent;
    };
    pick(profile.tone || 'Balanced');
    $('#tones').onclick = e => { const t = e.target.closest('.tone'); if (t) { pick(t.dataset.v); save({tone: t.dataset.v}); } };
  },
  privacy() {
    syncToggles();
    $('#micPerm').innerHTML = '<span class="pill wait">Checking…</span>';
    api.mic_permission().then(p => {
      $('#micPerm').innerHTML = p === 'allowed' ? '<span class="pill ok">Allowed ✓</span>'
        : '<span class="pill bad">Blocked</span><button class="link" id="permFix">Open settings</button>';
      if (p !== 'allowed') $('#permFix').onclick = () => api.open_mic_settings();
    });
    renderKeyBox();
  },
  mic() {
    const meter = $('#meter');
    if (!meter.children.length) for (let i = 0; i < 18; i++) meter.appendChild(document.createElement('i'));
    let heard = 0, poll;
    const restart = async () => {
      heard = 0; $('#micNext').disabled = true; $('#micErr').textContent = '';
      const r = await api.meter_start();
      if (!r.ok) $('#micErr').textContent = `Couldn't open this microphone (${r.error}). Try another one.`;
    };
    const render = async rescan => {
      const {devices, selected} = await api.mics(rescan);
      const opts = [{name: null, label: 'System default', sub: 'Whatever Windows uses'}].concat(
        devices.map(d => ({...d, sub: d.label !== d.name ? '' : '', tag: d.bluetooth ? 'Bluetooth' : ''})));
      $('#devs').innerHTML = opts.map((d, k) => `<button class="dev ${d.name === selected ? 'sel' : ''}" data-k="${k}">
        <span class="radio"></span><div><b>${esc(d.label)}</b>${d.sub ? `<small>${esc(d.sub)}</small>` : ''}</div>
        ${d.tag ? `<span class="tag">${d.tag}</span>` : ''}</button>`).join('');
      $('#devs').onclick = async e => {
        const b = e.target.closest('.dev'); if (!b) return;
        $$('#devs .dev').forEach(x => x.classList.toggle('sel', x === b));
        await api.set_mic(opts[+b.dataset.k].name); heard = 0; $('#micNext').disabled = true;
      };
    };
    $('#micRefresh').onclick = async () => { await api.meter_stop(); await render(true); restart(); };
    render(false).then(restart);
    poll = setInterval(async () => {
      const lvl = await api.meter_level();
      meter.classList.toggle('live', lvl > 4);
      meter.querySelectorAll('i').forEach((b, k) => {
        const shape = Math.sin((k + 1) / 19 * Math.PI);
        b.style.height = Math.max(8, Math.min(70, lvl * 0.9 * shape * (0.7 + Math.random() * 0.6))) + 'px';
      });
      if (lvl > 15 && ++heard > 4) $('#micNext').disabled = false;
    }, 70);
    return () => { clearInterval(poll); api.meter_stop(); };
  },
  keys() {
    $('#keycaps').innerHTML = st.hotkey.map((k, i) => `<div class="key" data-id="${st.hotkey_ids[i]}">${
      k === 'Win' ? '<svg width="18" height="18" viewBox="0 0 18 18" fill="currentColor"><rect width="8" height="8"/><rect x="10" width="8" height="8"/><rect y="10" width="8" height="8"/><rect x="10" y="10" width="8" height="8"/></svg>' : esc(k)
    }<small>${esc(k)}</small></div>`).join('');
    $('#keyOk').textContent = '';
    const poll = setInterval(async () => {
      const {pressed, active} = await api.keys();
      $$('#keycaps .key').forEach(c => c.classList.toggle('down', pressed.includes(c.dataset.id)));
      if (active) { $('#keyOk').textContent = '✓ Perfect, that’s your shortcut.'; $('#keyNext').disabled = false; }
    }, 50);
    return () => clearInterval(poll);
  },
  try() {
    api.dictation(true);
    const demos = [
      {app: 'Team chat', who: '<div class="av">M</div><div><b>Maya</b>Did the report go out yet?</div>'},
      {app: 'Email · To: Sam Rivera', who: '<div class="av">S</div><div><b>Re: Launch plan</b>Can we move our sync?</div>'},
      {app: 'Notes', who: '<div class="av">✎</div><div><b>Weekend</b>Things to do</div>'}];
    const box = $('#tryBox');
    const pick = i => {
      $$('#tabs .tab').forEach((t, k) => t.classList.toggle('on', k === i));
      $('#appName').textContent = demos[i].app; $('#thread').innerHTML = demos[i].who;
      $('#raw').textContent = ''; box.value = ''; box.focus(); pick.i = i;
    };
    $('#tabs').onclick = e => { const t = e.target.closest('.tab'); if (t) pick($$('#tabs .tab').indexOf(t)); };
    pick(0);
    const live = $('#tryLive');
    listeners = [ev => {
      live.className = 'live';
      if (ev.kind === 'recording') { live.classList.add('rec'); live.textContent = 'Listening… let go when you’re done'; }
      else if (ev.kind === 'processing') { live.classList.add('proc'); live.textContent = 'Cleaning up…'; }
      else if (ev.kind === 'result') {
        $('#raw').textContent = 'Heard: ' + ev.entry.raw;
        live.textContent = ev.raw_pasted ? 'Cleanup was busy, so your words were pasted as heard.'
          : `Done in ${(ev.entry.total_ms / 1000).toFixed(1)} s after you let go`;
        $$('#tabs .tab')[pick.i].classList.add('done');
      } else if (ev.kind === 'skipped') live.textContent = 'Didn’t catch that. Hold the keys a little longer.';
      else if (ev.kind === 'error') { live.textContent = ev.message; toast(ev.message, true); }
    }];
    return () => { listeners = []; };
  },
};

// sign up
$('#signupBtn').onclick = () => {
  const name = $('#name').value.trim(), email = $('#email').value.trim();
  if (!name) return $('#signupErr').textContent = 'Please add your name.';
  if (email && !/^\S+@\S+\.\S+$/.test(email)) return $('#signupErr').textContent = 'That email doesn’t look right.';
  $('#signupErr').textContent = ''; save({name, email}); go(1);
};
$('#googleBtn').onclick = () => { if ($('#name').value.trim()) save({name: $('#name').value.trim()}); go(1); };
$('#name').onkeydown = $('#email').onkeydown = e => { if (e.key === 'Enter') $('#signupBtn').click(); };

// API key (privacy screen)
function renderKeyBox() {
  const form = $('#keyForm'), toggle = $('#keyToggle');
  $('#keyErr').textContent = '';
  if (st.has_user_key) {
    toggle.textContent = 'Using your own Groq API key ✓'; toggle.onclick = null; form.classList.add('hidden');
    $('#privacyNext').disabled = false;
  } else if (st.has_default_key) {
    toggle.textContent = 'Use your own Groq API key (optional)';
    toggle.onclick = () => form.classList.toggle('hidden');
    $('#privacyNext').disabled = false;
  } else {
    toggle.textContent = 'A Groq API key is needed'; toggle.onclick = null; form.classList.remove('hidden');
    $('#privacyNext').disabled = true;
  }
}
$('#keySave').onclick = async () => {
  $('#keySave').disabled = true; $('#keyErr').textContent = 'Checking key…';
  const r = await api.check_key($('#keyInput').value);
  $('#keySave').disabled = false;
  if (r.ok) { st.has_user_key = true; st.has_default_key = false; $('#keyInput').value = ''; renderKeyBox(); toast('Key saved'); }
  else $('#keyErr').textContent = r.error;
};

$('#finishBtn').onclick = async () => { await api.finish(); st.onboarded = true; showHome(); };

// ---------------- home ----------------
function fmtTime(t) { return new Date(t).toLocaleTimeString([], {hour: 'numeric', minute: '2-digit'}).toLowerCase(); }
const ICON_RETRY = '<svg viewBox="0 0 16 16" width="12" height="12"><path d="M13 8a5 5 0 1 1-1.5-3.6M13 2.5v2.9h-2.9" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const ICON_FOLDER = '<svg viewBox="0 0 16 16" width="12" height="12"><path d="M2 4h4.2l1.5 1.6H14V13H2z" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/></svg>';
const GONE = 'This recording was deleted. Audio is kept for 30 days.';
function entryHtml(e, isNew) {
  const tools = e.audio ? `<span class="tools" data-audio="${esc(e.audio)}"><button class="play" title="Play recording">▶</button>`
    + `<button class="retry" title="${e.pending ? 'Try sending now' : 'Transcribe again'}">${ICON_RETRY}</button><button class="folder" title="Show in folder">${ICON_FOLDER}</button></span>` : '';
  // A clip saved while offline has no text yet; one Groq couldn't read keeps its audio to try again.
  const text = e.pending ? '<span class="note">Waiting to send. Saved on this PC; it goes as soon as you’re back online.</span>'
    : e.failed ? '<span class="note">Couldn’t transcribe this recording. Press ↻ to try again.</span>' : esc(e.text);
  return `<div class="entry ${isNew ? 'new' : ''} ${e.pending ? 'pending' : ''}"><time>${fmtTime(e.t)}</time><div>${text}</div><em>${tools}${e.pending ? 'waiting' : (e.total_ms / 1000).toFixed(1) + ' s'}</em></div>`;
}
// Play (one recording at a time; pressing the playing one stops it), transcribe again, show in folder.
let player = null, playBtn = null;
document.addEventListener('click', async ev => {
  const b = ev.target.closest('.tools button'); if (!b) return;
  const name = b.parentElement.dataset.audio;
  if (b.classList.contains('folder')) {
    if (!await api.show_audio(name)) toast(GONE);
  } else if (b.classList.contains('retry')) {
    if (b.disabled) return;
    b.disabled = true; b.classList.add('busy');
    const r = await api.retry(name);
    b.disabled = false; b.classList.remove('busy');
    if (!r.ok) return toast(r.error, true);
    if (r.queued) return toast('Trying to send it now…');
    // The same entry can be on Today and in History.
    if (r.entry) $$('.tools').filter(t => t.dataset.audio === name).forEach(t => t.closest('.entry').children[1].textContent = r.entry.text);
    renderStats(r.stats); toast('Transcribed again');
  } else {
    const same = b === playBtn;
    if (player) { player.pause(); playBtn.textContent = '▶'; player = playBtn = null; }
    if (same) return;
    const url = await api.audio(name);
    if (!url) return toast(GONE);
    player = new Audio(url); playBtn = b; b.textContent = '■';
    player.onended = () => { if (playBtn === b) { b.textContent = '▶'; player = playBtn = null; } };
    player.play();
  }
});
function renderStats(s) {
  $('#sWords').innerHTML = `${s.words.toLocaleString()}<span>words</span>`;
  $('#sWpm').innerHTML = `${s.wpm || '–'}<span>wpm</span>`;
  $('#sStreak').innerHTML = `${s.streak}<span>day streak</span>`;
}
async function renderToday() {
  const today = new Date().toDateString();
  const items = (await api.history('')).filter(e => new Date(e.t).toDateString() === today);
  $('#today').innerHTML = items.length ? items.map(e => entryHtml(e)).join('')
    : `<div class="empty">Nothing yet today.<br>Click into any app, hold ${st.hotkey.join(' + ')}, and start talking.</div>`;
}
async function renderHistory() {
  const items = await api.history($('#search').value);
  $('#allHist').innerHTML = items.length ? items.map(e => entryHtml(e)).join('')
    : `<div class="empty">${$('#search').value ? 'No matches.' : profile.keep_history ? 'Your dictations will show up here.' : 'History is turned off in Settings.'}</div>`;
}
async function fillMicSelects(rescan = false) {
  const {devices, selected} = await api.mics(rescan);
  const html = `<option value="">System default</option>` + devices.map(d =>
    `<option value="${esc(d.name)}" ${d.name === selected ? 'selected' : ''}>${esc(d.label)}${d.bluetooth ? ' (Bluetooth)' : ''}</option>`).join('');
  ['#homeMic', '#setMic'].forEach(id => { $(id).innerHTML = html; });
}
function setStatus(kind, text) {
  const s = $('#status'), b = $('#banner');
  s.className = 'status ' + kind; s.querySelector('span').textContent = text;
  b.classList.toggle('rec', kind === 'rec');
}
const readyText = () => `Listening for ${st.hotkey.join(' + ')}`;

async function showHome() {
  $('#onb').classList.add('hidden'); $('#home').classList.remove('hidden');
  if (leaveHook) { leaveHook(); leaveHook = null; }
  const h = new Date().getHours();
  $('#hello').textContent = (h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening') + (profile.name ? `, ${profile.name}` : '');
  ['#homeTone', '#setTone'].forEach(id => $(id).value = profile.tone || 'Balanced');
  $('#keyStatus').textContent = st.has_user_key ? 'Using your own Groq key' : st.has_default_key ? 'Using the built-in key' : 'No key set';
  syncToggles(); setStatus('', readyText());
  renderStats(await api.stats()); renderToday(); fillMicSelects();
  listeners = [ev => {
    if (ev.kind === 'recording') setStatus('rec', 'Recording… let go when you’re done');
    else if (ev.kind === 'processing') setStatus('proc', 'Cleaning up…');
    else {
      setStatus('', readyText());
      if (ev.kind === 'result') {
        renderStats(ev.stats);
        if (ev.delayed || $('#today .entry.pending')) renderToday();  // replaces its "Waiting to send" row
        else {
          const empty = $('#today .empty'); if (empty) empty.remove();
          $('#today').insertAdjacentHTML('afterbegin', entryHtml(ev.entry, true));
        }
        if (!$('#pHistory').classList.contains('hidden')) renderHistory();
        if (!ev.pasted) toast('Your dictation is on the clipboard. Press Ctrl+V to paste it.');
        else if (ev.raw_pasted) toast('Cleanup was busy, so your words were pasted as heard.');
      } else if (ev.kind === 'offline') {
        toast(ev.message);
        renderToday();
        if (!$('#pHistory').classList.contains('hidden')) renderHistory();
      } else if (ev.kind === 'skipped') toast('Didn’t catch that. Hold the keys a little longer.');
      else if (ev.kind === 'error') toast(ev.message, true);
    }
  }];
}

$$('.nav[data-page]').forEach(n => n.onclick = () => {
  $$('.nav[data-page]').forEach(x => x.classList.toggle('on', x === n));
  $$('.page').forEach(p => p.classList.toggle('hidden', p.id !== n.dataset.page));
  if (n.dataset.page === 'pHistory') renderHistory();
  if (n.dataset.page === 'pDictionary') renderDictionary();
});

// ---------------- dictionary ----------------
async function renderDictionary(words) {
  words = words || await api.dictionary();
  $('#dictList').innerHTML = words.length
    ? words.map(w => `<span class="chip">${esc(w)}<button class="x" data-word="${esc(w)}" title="Remove">×</button></span>`).join('')
    : '<div class="empty" style="width:100%">No words yet. Add names or terms Saylo gets wrong, like your company or a product.</div>';
}
$('#dictForm').onsubmit = async ev => {
  ev.preventDefault();
  const added = $('#dictInput').value.split(',').map(w => w.trim()).filter(Boolean);
  if (!added.length) return;
  renderDictionary(await api.set_dictionary([...await api.dictionary(), ...added]));
  $('#dictInput').value = '';
};
$('#dictList').onclick = async ev => {
  const x = ev.target.closest('.x'); if (!x) return;
  renderDictionary(await api.set_dictionary((await api.dictionary()).filter(w => w !== x.dataset.word)));
};
$('#search').oninput = () => { clearTimeout(renderHistory._t); renderHistory._t = setTimeout(renderHistory, 200); };
['#homeMic', '#setMic'].forEach(id => $(id).onchange = async e => {
  await api.set_mic(e.target.value || null); fillMicSelects(); toast('Microphone updated');
});
$('#setMicRefresh').onclick = () => fillMicSelects(true);
['#homeTone', '#setTone'].forEach(id => $(id).onchange = e => {
  save({tone: e.target.value}); ['#homeTone', '#setTone'].forEach(x => $(x).value = e.target.value);
});
$('#clearHist').onclick = async () => {
  if (!confirm('Delete all saved dictations and recordings on this PC? This can’t be undone.')) return;
  renderStats(await api.clear_history()); renderToday(); toast('History deleted');
};
$('#setKeySave').onclick = async () => {
  const r = await api.check_key($('#setKey').value);
  if (r.ok) { st.has_user_key = true; st.has_default_key = false; $('#setKey').value = ''; $('#keyStatus').textContent = 'Using your own Groq key'; toast('Key saved'); }
  else toast(r.error, true);
};
$('#rerun').onclick = async () => { await api.restart_setup(); st.onboarded = false; showOnb(0); };
$('#openLog').onclick = () => api.open_log();
