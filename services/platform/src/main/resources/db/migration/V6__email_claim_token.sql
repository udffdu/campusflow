-- A fresh token fences every claim, including claims after a manual retry resets attempts.
-- In-flight tasks from the previous application version are reclaimed after lease expiry.
ALTER TABLE email_notification_tasks ADD COLUMN claim_token uuid;
