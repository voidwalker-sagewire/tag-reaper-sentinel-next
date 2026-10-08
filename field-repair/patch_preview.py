"""Narrow, assertion-checked bytecode recovery patch, not original Rev5 source."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
app = root / 'smali_classes3/com/herdmate/tagreapersentinel'

def replace_once(text, before, after):
    assert text.count(before) == 1, (before, text.count(before))
    return text.replace(before, after, 1)

activity = app / 'ProcessingActivity.smali'
s = activity.read_text()
s = replace_once(s, '# instance fields', '# instance fields\n.field private nextLiveGate:Lcom/herdmate/tagreapersentinel/LiveRefreshGate;\n')
start = s.index('.method private processScaleFrame(')
end = s.index('.end method', start)
part = s[start:end]
part = replace_once(part, 'invoke-direct {p0}, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->refreshUi()V',
                    'invoke-virtual {p0}, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->requestLiveRefresh()V')
s = s[:start] + part + s[end:]
start = s.index('.method protected onCreate(')
end = s.index('.end method', start)
part = s[start:end]
super_call = 'invoke-super {p0, p1}, Landroidx/appcompat/app/AppCompatActivity;->onCreate(Landroid/os/Bundle;)V'
part = replace_once(part, super_call, super_call + '\n\n    invoke-static {p0}, Lcom/herdmate/tagreapersentinel/SentinelDiagnostics;->install(Landroid/app/Activity;)V')
s = s[:start] + part + s[end:]
s = replace_once(s, 'REAPER SENTINEL  \\u00b7  GUIDED PROCESSING', 'SENTINEL NEXT R1  \\u00b7  GUIDED PROCESSING')
s += '''
.method public requestLiveRefresh()V
    .locals 3
    iget-boolean v0, p0, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->destroyed:Z
    if-nez v0, :done
    iget-object v0, p0, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->nextLiveGate:Lcom/herdmate/tagreapersentinel/LiveRefreshGate;
    if-nez v0, :request
    new-instance v0, Lcom/herdmate/tagreapersentinel/LiveRefreshGate;
    iget-object v1, p0, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->main:Landroid/os/Handler;
    new-instance v2, Lcom/herdmate/tagreapersentinel/NextLiveRefresh;
    invoke-direct {v2, p0}, Lcom/herdmate/tagreapersentinel/NextLiveRefresh;-><init>(Lcom/herdmate/tagreapersentinel/ProcessingActivity;)V
    invoke-direct {v0, v1, v2}, Lcom/herdmate/tagreapersentinel/LiveRefreshGate;-><init>(Landroid/os/Handler;Ljava/lang/Runnable;)V
    iput-object v0, p0, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->nextLiveGate:Lcom/herdmate/tagreapersentinel/LiveRefreshGate;
    :request
    invoke-virtual {v0}, Lcom/herdmate/tagreapersentinel/LiveRefreshGate;->request()V
    :done
    return-void
.end method

.method public flushLiveRefresh()V
    .locals 1
    iget-boolean v0, p0, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->destroyed:Z
    if-nez v0, :done
    invoke-direct {p0}, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->refreshUi()V
    iget-boolean v0, p0, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->zoneTestMode:Z
    if-eqz v0, :done
    invoke-direct {p0}, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->refreshZoneTest()V
    :done
    return-void
.end method
'''
activity.write_text(s)

callback = app / 'ProcessingActivity$1.smali'
s = callback.read_text()
s = replace_once(s, 'invoke-static {v1}, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->access$1000(Lcom/herdmate/tagreapersentinel/ProcessingActivity;)V',
                  'invoke-virtual {v1}, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->requestLiveRefresh()V')
s = replace_once(s, 'invoke-static {v2}, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->access$2300(Lcom/herdmate/tagreapersentinel/ProcessingActivity;)V',
                  'invoke-virtual {v2}, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->requestLiveRefresh()V')
s = replace_once(s, 'const-wide/16 v4, 0x3e8', 'const-wide/16 v4, 0x1388') # Repeat evidence checkpoint: 5s; transitions unchanged.
callback.write_text(s)

latch = app / 'StableWeightLatch.smali'
s = latch.read_text()
anchor = '    :cond_1\n    iget-object v1, p0, Lcom/herdmate/tagreapersentinel/StableWeightLatch;->readings:Ljava/util/Deque;'
guard = '''    :cond_1
    iget-wide v1, p1, Lcom/herdmate/tagreapersentinel/SellEtonFrameParser$Result;->pounds:D
    const-wide/16 v3, 0x0
    cmpl-double v5, v1, v3
    if-lez v5, :cond_7
    invoke-static {v1, v2}, Ljava/lang/Double;->isNaN(D)Z
    move-result v5
    if-nez v5, :cond_7
    invoke-static {v1, v2}, Ljava/lang/Double;->isInfinite(D)Z
    move-result v5
    if-nez v5, :cond_7
    iget-object v1, p0, Lcom/herdmate/tagreapersentinel/StableWeightLatch;->readings:Ljava/util/Deque;'''
s = replace_once(s, anchor, guard)
latch.write_text(s)

manifest = root / 'AndroidManifest.xml'
s = manifest.read_text().replace('com.herdmate.tagreapersentinel.preview', 'com.herdmate.tagreapersentinel.next')
# Distinct application process and startup provider; original app data untouched.
manifest.write_text(s)
strings = root / 'res/values/strings.xml'
strings.write_text(replace_once(strings.read_text(), '<string name="app_name">Sentinel Preview</string>', '<string name="app_name">Sentinel Next R1</string>'))
metadata = root / 'apktool.yml'
s = replace_once(metadata.read_text(), 'versionCode: 8', 'versionCode: 9')
s = replace_once(s, 'versionName: 0.7.0', 'versionName: 0.7.1-next-r1')
metadata.write_text(s)
print('Applied stream refresh, weight guard, diagnostics and separate package patches.')
