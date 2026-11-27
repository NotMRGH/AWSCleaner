# AWSCleaner

AWSCleaner monitors an Amazon Lightsail instance over SSH, rotates its public IPv4 address when reachability checks fail, and synchronizes selected ArvanCloud DNS A records. It runs on a Linux host with Java 17 and includes a Bash menu for managing the application.

AWS requests use SigV4 and a SOCKS5 proxy. SSH, ArvanCloud API requests, and the HTTPS connectivity probes go directly from the host running AWSCleaner. The scheduler waits until a check finishes before starting the configured delay for the next check.

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

`--init-config` leaves an existing file in place. `--check-config` validates the YAML and environment variables. The application reads its configuration at startup, so choose **Restart** after editing settings or replacing a key.

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
| `application.state-file`, `logging.file` | Persistent rotation journal and rolling Java log. Relative paths use the working directory. |

## Logs and recovery

Choose **5) Show Logs** for the last 100 lines of `output.log`, or use the shell to follow either log.

```bash
tail -f output.log
tail -f logs/application.log
```

`output.log` captures console output from the menu. The Java application log rolls by date and size. Keep `state/rotation.json` between runs. It records the instance ARN, recent rotation attempts, and pending mutations. A failed swap can restore the previous Static IP when AWS still has it available. If the instance previously used a dynamic address, AWS may assign a different one when dynamic assignment is restored. Rotation requires SSH failure, even during a DNS permission outage.