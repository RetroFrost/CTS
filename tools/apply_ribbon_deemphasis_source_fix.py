from pathlib import Path
p=Path('android/app/src/main/java/io/github/retrofrost/cts/android/RibbonFrameRenderer.kt')
s=p.read_text(encoding='utf-8')
old='''        fun legacyTrigger(nextIndex: Int): Float {
            val start = RibbonTimeline.cardStartFrame(project, spec, nextIndex)
            return if (nextIndex < 4) start + 99f else start.toFloat()
        }
'''
new='''        fun legacyTrigger(nextIndex: Int): Float {
            val start = RibbonTimeline.cardStartFrame(project, spec, nextIndex)
            // The source starts de-emphasising an earlier badge as the next badge
            // begins its visible entry, not ~100 frames later.
            return if (nextIndex < 4) start + OPENING_BADGE_FIRST_FRAME.toFloat() else start.toFloat()
        }
'''
if old not in s: raise SystemExit('trigger target not found')
s=s.replace(old,new,1)
old2='''            scale = lerp(1f, 272f / 298f, p)
'''
new2='''            scale = lerp(1f, 0.90f, p)
'''
if old2 not in s: raise SystemExit('first target scale not found')
s=s.replace(old2,new2,1)
old3='''            if (p > 0f) scale = lerp(272f / 298f, 248f / 298f, p)
'''
new3='''            if (p > 0f) scale = lerp(0.90f, 0.75f, p)
'''
if old3 not in s: raise SystemExit('second target scale not found')
s=s.replace(old3,new3,1)
p.write_text(s,encoding='utf-8')
