#!/usr/bin/env bash
set -euo pipefail

# All UI fixtures run in the isolated debug package, never the installed release.
classes=(
  net.ankio.ledger.LicenseDisclosureTest
  net.ankio.ledger.ExecutionLogTest
  net.ankio.ledger.ocr.LocalModeSettingsTest
  net.ankio.ledger.data.LedgerCursorOptimizationTest
  net.ankio.ledger.data.LedgerCandidateOptimizationTest
  net.ankio.ledger.data.RecognitionRecoveryOptimizationTest
  net.ankio.ledger.data.LedgerQuery056Test
  net.ankio.ledger.data.AutomaticSupplementTest
  net.ankio.ledger.data.RefundPersistenceTest
  net.ankio.ledger.data.LedgerIdentity056Test
  net.ankio.ledger.data.MerchantEnrichmentTest
  net.ankio.ledger.data.LedgerUndo056Test
  net.ankio.ledger.data.CaptureSupplementTest
  net.ankio.ledger.PaymentRecognitionQueueTest
  net.ankio.ledger.PaymentVisitsTest
  net.ankio.ledger.BackgroundSettingsSyncTest
  net.ankio.ledger.PermissionTrimTest
  net.ankio.ledger.CaptureReadinessTest
  net.ankio.ledger.CaptureRestoreTest
  net.ankio.ledger.RecognitionBannerFallbackTest
  net.ankio.ledger.SetupStepsTest
  net.ankio.ledger.ocr.IconGeneralityTest
  net.ankio.ledger.ocr.LocalHeaderIconTest
  net.ankio.ledger.IconLocationRegressionTest
  net.ankio.ledger.MerchantIconTest
  net.ankio.ledger.ocr.LocalRecognitionClientTest
  net.ankio.ledger.data.AccountPostingTest
  net.ankio.ledger.data.ChannelDefaultAccountTest
  net.ankio.ledger.data.MonthlyBudgetTest
  net.ankio.ledger.data.ReimbursementTest
  net.ankio.ledger.data.BillReportTest
  net.ankio.ledger.data.ReportEvidenceTest
  net.ankio.ledger.data.AccountNameTest
  net.ankio.ledger.data.RefundBackupTest
  net.ankio.ledger.AccountIdentityCurrencyUiTest
  net.ankio.ledger.BillReportUiTest
  net.ankio.ledger.data.OfflineBillsTest
  net.ankio.ledger.data.PlatformImportAccountsTest
  net.ankio.ledger.data.LedgerBatchDeletionTest
  net.ankio.ledger.data.InvalidRecognitionCleanupTest
  net.ankio.ledger.data.ImportBatchesTest
  net.ankio.ledger.data.ScreenshotPolicyTest
  net.ankio.ledger.data.LedgerArchiveTest
  net.ankio.ledger.DataManagementUiTest
  net.ankio.ledger.PlatformImportAccountsUiTest
  net.ankio.ledger.UpdateUiSecurityTest
  net.ankio.ledger.data.ResultMetadataTest
  net.ankio.ledger.SummaryPrivacyUiTest
  net.ankio.ledger.RecognitionEditingUiTest
  net.ankio.ledger.ResultAccountPopupTest#touchGesturesResumeExpiryOutsideAndKeepMenuLifetime
)
class_list=$(IFS=,; printf '%s' "${classes[*]}")
collect_screenshots() {
  adb pull /sdcard/Pictures/platform-import-qa ci-artifacts/platform-import-qa || true
  adb pull /sdcard/Pictures/recognition-editing-qa ci-artifacts/recognition-editing-qa || true
}
trap collect_screenshots EXIT

test_status=0
bash ./gradlew -PledgerAbi=x86_64 -PledgerQa=true "-Pandroid.testInstrumentationRunnerArguments.class=$class_list" :app:connectedDebugAndroidTest --stacktrace || test_status=$?
if [ -d app/build/reports/androidTests ]; then
  cp -R app/build/reports/androidTests ci-artifacts/standard-android-tests
fi
if [ "$test_status" -eq 0 ]; then
  adb shell wm size 720x1280
  adb shell wm density 360
  adb shell settings put system font_scale 1.5
  adb shell cmd uimode night yes
  bash ./gradlew -PledgerAbi=x86_64 -PledgerQa=true '-Pandroid.testInstrumentationRunnerArguments.class=net.ankio.ledger.RecognitionEditingUiTest#keyboardKeepsSaveAndCancelReachable' :app:connectedDebugAndroidTest --stacktrace || test_status=$?
fi
exit "$test_status"
