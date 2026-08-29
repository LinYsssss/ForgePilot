-- DingTalk requires every robot to carry at least one of three protections, and a
-- deployment that cannot enable signing is left with 自定义关键词: the robot drops
-- any message whose content does not contain a configured word. It drops it while
-- answering HTTP 200, so without this column the product could only fail silently
-- -- which is exactly what it did.
--
-- Not a credential, so not encrypted: the keyword is a routing condition, and the
-- person configuring it needs to read it back to check it matches the robot. It is
-- nullable because a signed robot needs no keyword at all.
--
-- The legacy implementation this replaces put the keyword in the message title and
-- carried it as one deployment-wide property. Per project is the right scope here
-- for the same reason the webhook URL is: two projects report to two chats, and two
-- chats can have two different keywords.

ALTER TABLE project_notification_channel ADD COLUMN keyword VARCHAR(64);
