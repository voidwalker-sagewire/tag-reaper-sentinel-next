.class public final Lcom/herdmate/tagreapersentinel/NextLiveRefresh;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;
.field private final activity:Lcom/herdmate/tagreapersentinel/ProcessingActivity;

.method public constructor <init>(Lcom/herdmate/tagreapersentinel/ProcessingActivity;)V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lcom/herdmate/tagreapersentinel/NextLiveRefresh;->activity:Lcom/herdmate/tagreapersentinel/ProcessingActivity;
    return-void
.end method

.method public run()V
    .locals 1
    iget-object v0, p0, Lcom/herdmate/tagreapersentinel/NextLiveRefresh;->activity:Lcom/herdmate/tagreapersentinel/ProcessingActivity;
    invoke-virtual {v0}, Lcom/herdmate/tagreapersentinel/ProcessingActivity;->flushLiveRefresh()V
    return-void
.end method
