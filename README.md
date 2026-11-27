# AWSCleaner

AWSCleaner monitors an Amazon Lightsail instance over SSH, rotates its public IPv4 address when reachability checks fail, and synchronizes selected ArvanCloud DNS A records. An independent traffic monitor tracks monthly Lightsail transfer and sends Persian Telegram alerts. The application runs on a Linux host with Java 17 and includes a Bash menu.

AWS requests use SigV4. AWS and Telegram share a SOCKS5 client. SSH, ArvanCloud API requests, and the HTTPS connectivity probes go directly from the host running AWSCleaner. Each scheduler waits until its own check finishes before starting the configured delay.

## Build and open the menu

Build with JDK 17 and Maven.

```bash
mvn clean verify
```

On Linux, place `awscleaner.sh` and `AWSCleaner.jar` in the same directory. When you run the script from a project checkout, it also finds the build output at `target/AWSCleaner.jar`. The menu uses Bash and the standard Ubuntu utilities. Install `nano` to use the menu's editor.

```bash
bash awscleaner.sh
```

When `config.yml` is missing, AWSCleaner copies the bundled template byte for byte. The menu puts it beside the script. The JAR uses the current working directory by default or the path supplied with `--config`. On the first direct launch, the JAR exits after creating the template so you can fill in your settings before the first check. [Bundled YAML](src/main/resources/config.yml)

Choose **6) Edit Config** to open the file in `nano`. Press **Ctrl+O**, **Enter** to save, then **Ctrl+X** to exit. Set `application.dry-run: true` for the first check. Choose **1) Start** to run in the background. The other options stop or restart the process, show its status, and display the last 100 lines of console output. Exiting the menu leaves the process running. After a host reboot, open the menu and choose **Start**.

### Run the JAR directly

With the JAR in the working directory, use these commands. The verification and run commands read credentials from the shell environment. The Bash menu also loads the local environment file described below.

```bash
java -jar AWSCleaner.jar --init-config
java -jar AWSCleaner.jar --check-config
java -jar AWSCleaner.jar
```

`--init-config` leaves an existing file in place. `--check-config` validates the YAML and reports optional traffic settings that would be disabled at startup. The application reads its configuration at startup, so choose **Restart** after editing settings or replacing a key.

## Configure AWS Lightsail

### Find the instance name, Region, and ARN

Open **Lightsail → Instances** and copy the exact instance name into `aws.instance-name`. Use the Region code for `aws.region`. For example, Frankfurt Zone A is displayed as `eu-central-1a`, and the Region value to enter is `eu-central-1`. AWSCleaner reads the current public IPv4 address from AWS. [Lightsail Regions and Availability Zones](https://docs.aws.amazon.com/lightsail/latest/userguide/understanding-regions-and-availability-zones-in-amazon-lightsail.html)

The IAM policy below also needs the instance ARN. For this one-time lookup, open CloudShell in the AWS console and run the following with your actual instance name and Region. The ARN contains your 12-digit account ID. [AWS guidance on obtaining an instance ARN](https://docs.aws.amazon.com/lightsail/latest/userguide/security_iam_resource-based-policy-examples.html)

```bash
aws lightsail get-instance --region eu-central-1 --instance-name 'MyServer' --query 'instance.arn' --output text
```

### Create the IAM user and attach its policy

1. Open **IAM → IAM users → Create user**. Create a dedicated user, for example `awscleaner`, and leave console sign-in disabled.
2. If you already created a customer-managed policy, choose **Attach policies directly** and select it. Otherwise, continue without selecting a group and create the user.
3. Open the user and choose **Permissions → Add permissions → Create inline policy → JSON**. Paste the policy below and save it. Leave the optional permissions boundary unset for this setup. The boundary screen controls the maximum permissions and the inline policy grants access. [AWS IAM policy setup](https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies_manage-attach-detach.html)

Replace the sample Region, account ID, and instance ARN. The policy limits attachment to the specified instance and grants access to Static IP operations in that account and Region. If you permanently disable `release-old-ip`, you can remove `lightsail:ReleaseStaticIp`. [Lightsail IAM actions and resource types](https://docs.aws.amazon.com/service-authorization/latest/reference/list_lightsail.html)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "lightsail:GetInstance",
        "lightsail:GetInstanceState",
        "lightsail:GetStaticIps",
        "lightsail:GetStaticIp",
        "lightsail:GetOperation",
        "lightsail:GetOperationsForResource",
        "lightsail:AllocateStaticIp"
      ],
      "Resource": "*",
      "Condition": { "StringEquals": { "aws:RequestedRegion": "eu-central-1" } }
    },
    {
      "Effect": "Allow",
      "Action": "lightsail:AttachStaticIp",
      "Resource": [
        "arn:aws:lightsail:eu-central-1:123456789012:Instance/INSTANCE_ID",
        "arn:aws:lightsail:eu-central-1:123456789012:StaticIp/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "lightsail:DetachStaticIp",
        "lightsail:ReleaseStaticIp"
      ],
      "Resource": "arn:aws:lightsail:eu-central-1:123456789012:StaticIp/*"
    }
  ]
}
```

### Create the AWS access key

The **Manage access keys** button on the IAM dashboard refers to the root account. To create the application key, open the dedicated IAM user and go to **Security credentials → Access keys → Create access key**. Choose **Application running outside AWS** when the launcher runs on an external Linux host. Save the access key ID as `AWS_ACCESS_KEY_ID` and the secret access key as `AWS_SECRET_ACCESS_KEY`. AWS shows the secret during key creation. Keep `aws.session-token` empty for a regular IAM access key. If you use temporary STS credentials, configure `"${AWS_SESSION_TOKEN}"` and restart after replacing the credentials. [AWS access key documentation](https://docs.aws.amazon.com/IAM/latest/UserGuide/access-keys-admin-managed.html)

## Monitor Lightsail traffic and send Telegram alerts

Copy the `traffic-monitor` section into an existing config to enable the monitor. The bundled config checks Frankfurt, Ireland, and London at startup and waits 10 minutes after each check. Set `regions` to your Lightsail Regions, `check-on-startup` and `check-interval-minutes` to your schedule, and `warning-percentages` to your thresholds from 1 to 100. The supplied thresholds are 80% and 90%. Set `traffic-monitor.enabled: false` to disable the monitor or `traffic-monitor.telegram.enabled: false` to keep traffic reporting in the logs. `application.dry-run` applies to IP rotation and DNS. Use the Telegram flag to control alerts during a dry run. [Bundled YAML](src/main/resources/config.yml)

Add the following read permissions as a separate statement in the Lightsail policy. Match the Region condition to the monitor's configured Regions. For a Region with half the standard allowance, also allow `lightsail:GetBundles` in `us-east-1` if the API omits the instance's allocation. `GetRegions` supplies display names for the alert, with the Region code available as a fallback. [Lightsail IAM actions](https://docs.aws.amazon.com/service-authorization/latest/reference/list_lightsail.html)

```json
{
  "Effect": "Allow",
  "Action": [
    "lightsail:GetInstances",
    "lightsail:GetBundles",
    "lightsail:GetInstanceMetricData",
    "lightsail:GetRegions"
  ],
  "Resource": "*",
  "Condition": {
    "StringEquals": { "aws:RequestedRegion": ["eu-central-1", "eu-west-1", "eu-west-2"] }
  }
}
```

Create a bot with BotFather and supply its token as `TELEGRAM_BOT_TOKEN` in your environment or `awscleaner.env`. Replace the sample `chat-id` with the destination's signed numeric ID. Start the bot from a private chat, add it to the receiving group, or give it posting access in the receiving channel. The monitor calls `sendMessage` through the AWS SOCKS5 client and respects Telegram's flood-control delay. [Bot setup](https://core.telegram.org/bots/features#botfather) · [sendMessage](https://core.telegram.org/bots/api#sendmessage) · [Bot rate limits](https://core.telegram.org/bots/faq#my-bot-is-hitting-limits-how-do-i-avoid-this)

With Telegram enabled, each application launch sends one Persian startup status message independently of the warning thresholds. It includes the first traffic estimates when `check-on-startup` is enabled. When the first check is scheduled for later, the startup message lists the monitored Regions and the wait until that check.

### How the estimate works

- The monitor groups instances from the configured AWS credentials by account, Region, and bundle. Dual-stack and IPv6-only variants of the same bundle share a pool. It reads the instance monthly allocation when available, falls back to the bundle catalog, and applies the documented half-allowance rule to that catalog in applicable Regions. The estimate uses binary units, so three concurrently observed 2048 GiB plans in a standard Region have a combined 6 TiB allowance. [Lightsail transfer rules](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-faq-data-transfer-allowance.html) · [MonthlyTransfer API](https://docs.aws.amazon.com/lightsail/2016-11-28/api-reference/API_MonthlyTransfer.html)
- AWS documents that deleting and recreating an instance preserves accumulated usage and that adding an instance does not reverse accrued outbound overage. The monitor estimates the pool size from the highest concurrent count it observed during the UTC month. It carries forward the last usable metrics for observed deleted instances by ARN. It cannot recover traffic after their last measurement or from instances created and deleted between checks. [Lightsail transfer rules and billing examples](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-faq-data-transfer-allowance.html)
- Each check reads hourly sums of `NetworkIn` and `NetworkOut` from the start of the UTC month. It allows for recent publication delay and withholds alerts when a required metric or allocation is missing. The logs identify incomplete groups. The network metrics cover all interfaces and do not identify billable outbound overage, so compare any charges against AWS Billing. [GetInstanceMetricData](https://docs.aws.amazon.com/lightsail/2016-11-28/api-reference/API_GetInstanceMetricData.html) · [Lightsail billing and usage](https://docs.aws.amazon.com/lightsail/latest/userguide/understanding-your-amazon-lightsail-bill.html)
- Keep `application.state-file` between runs. After Telegram confirms delivery, the monitor records that level and any lower crossed levels in the existing journal. A jump from below 80% to above 90% produces only the 90% alert. Unconfirmed attempts remain eligible on the next check with complete, above-threshold data. A lost Telegram acknowledgment or a crash before the successful delivery is saved can cause another attempt. Alert history resets at the start of the next UTC month. [Telegram API response format](https://core.telegram.org/bots/api#making-requests)

## Configure ArvanCloud DNS

1. In ArvanCloud, go to **Settings → IAM → Machine Users** and create a machine user, for example `lightsail-dns`. Save its key as `ARVAN_API_KEY`. For an existing machine user, use the key you saved when you created it. If the key is unavailable, create a dedicated machine user and save the new key when it appears. [ArvanCloud API keys](https://docs.arvancloud.ir/fa/developer-tools/api/api-key/)
2. In **Resource and Access Management**, open the workspace containing your domain. Use **+** beside the domain or its resource group, select the machine user that owns the saved API key, and grant DNS read and edit access. An active machine user still needs a rule granting access to the specific domain. [ArvanCloud access rules](https://docs.arvancloud.ir/fa/accounts/iam/policies)
3. Create the A records in ArvanCloud before starting the application. To manage `node1.example.com`, set `domain: "example.com"` and `subdomain: "node1"`. Use `"@"` for the zone apex. List records dedicated to this instance, since AWSCleaner updates every IPv4 origin in each matching A record. It preserves TTL, proxy settings, and the other writable record options. [ArvanCloud CDN API](https://www.arvancloud.ir/api-docs/cdn-4.0.yml)

## Configure the proxy and credentials

Set `aws.proxy.host` and `aws.proxy.port` to the SOCKS5 listener reachable from the machine running the launcher. The sample `127.0.0.1:1080` applies when the SOCKS5 listener runs on that same machine and port. Leave both proxy credential fields empty for an unauthenticated listener. For an authenticated listener, set both fields in YAML, for example with `"${SOCKS5_USERNAME}"` and `"${SOCKS5_PASSWORD}"`.

Leave the credential references in the generated YAML and export their values in your shell, or create `awscleaner.env` next to the launcher.

```ini
AWS_ACCESS_KEY_ID=your-aws-access-key-id
AWS_SECRET_ACCESS_KEY=your-aws-secret-access-key
ARVAN_API_KEY="Apikey your-arvan-api-key"
```

Restrict access to the file with `chmod 600 awscleaner.env`. The launcher reads literal `NAME=value` assignments and accepts optional matching quotes. Use the same file for any extra variables referenced in the YAML. The ArvanCloud key field accepts both the raw key and the value with its `Apikey` prefix.

## First check and troubleshooting

Start with `application.dry-run: true`, `check-on-startup: true`, and the five-minute interval. A successful first check fetches the instance's public IPv4 address, reports an SSH handshake, and reconciles the configured A records. After you have verified the settings and assigned DNS edit access, use `dry-run: false` and **Restart** to enable updates.

| What you see | What to check |
| --- | --- |
| `ArvanCloud GET returned HTTP 403` | Confirm the domain is in the expected ArvanCloud workspace and the machine user associated with `ARVAN_API_KEY` has DNS read access to that domain. If you only changed IAM permissions, the next check uses the same key. If you changed the key, update the environment and restart. |
| An AWS authorization or instance lookup error | Confirm that the key belongs to the IAM user with the Lightsail policy, that the Region is correct, and that the instance name matches the console. Check the SOCKS5 address and credentials if the connection itself fails. |
| Lightsail reports **Running**, but the SSH handshake fails | Confirm `ssh.port`, the instance firewall, and reachability from the machine running AWSCleaner. If you configured host key fingerprints, confirm they match the target host. |
| `Reconciling configured ArvanCloud A records` followed by `Next health check` with no warning | At `INFO` level a record already pointing to the verified IP can produce no separate line. In dry-run mode a difference is logged as `Dry run: would update ...`. A successful dry run verifies DNS reads. |
| A DNS target is missing | Confirm that the A record already exists and that its zone and name match `arvan.records`. |
| A Lightsail operation remains unconfirmed | Keep the state file and check the operation and Static IP name in Lightsail. AWSCleaner reconciles the recorded operation before making another IP change. |

### Does a healthy SSH connection still trigger DNS synchronization?

Yes. After a successful SSH handshake, each cycle compares the configured records with the current public IPv4. It sends an update only when a record differs and dry-run is disabled. This also covers manual IP changes. When the current address fails SSH, a replacement address must pass verification before it can be written to DNS.

### Settings to keep in mind

| Setting | Purpose |
| --- | --- |
| `application.check-interval-minutes` | Delay between the end of one check and the start of the next. The default is 5. |
| `ssh.port`, `ssh.attempts`, `ssh.retry-delay-seconds` | SSH port and retry behavior for each address. The default port is 22. |
| `network-check.urls` | Direct HTTPS probes used before rotation. At least one must return a successful response. |
| `ip-rotation.max-attempts`, `cooldown-minutes`, `max-rotations-per-hour` | Bound the attempts per cycle and over a rolling hour. |
| `ip-rotation.static-ip-quota` | Regional Static IP limit used by the preflight check. The sample value is 5. Account-level limits may differ. [Lightsail quotas](https://docs.aws.amazon.com/general/latest/gr/lightsail.html) |
| `ip-rotation.release-old-ip` | Cleanup of detached Static IPs recorded as program-owned. Hold this at `false` to retain those addresses. |
| `application.state-file`, `logging.file` | Persistent rotation and traffic journal and rolling Java log. Relative paths use the working directory. |

## Logs and recovery

Choose **5) Show Logs** for the last 100 lines of `output.log`, or use the shell to follow either log.

```bash
tail -f output.log
tail -f logs/application.log
```

`output.log` captures console output from the menu. The Java application log rolls by date and size. Keep `state/rotation.json` between runs. It records the instance ARN, recent rotation attempts, and pending mutations. A failed swap can restore the previous Static IP when AWS still has it available. If the instance previously used a dynamic address, AWS may assign a different one when dynamic assignment is restored. Rotation requires SSH failure, even during a DNS permission outage.
