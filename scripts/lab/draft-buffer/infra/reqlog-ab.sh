S=${DRAFT_LAB_WORK:?}
ST="20s:1280,60s:1280,20s:2500,60s:2500"
for R in 1 2; do
  STAGES=$ST JAR=api-final.jar LABEL=reqlog-before-$R bash $S/reqlog-ramp.sh > $S/reqlog-before-$R.log 2>&1
  STAGES=$ST JAR=api-reqlog.jar LABEL=reqlog-after-$R bash $S/reqlog-ramp.sh > $S/reqlog-after-$R.log 2>&1
done
echo ABDONE
