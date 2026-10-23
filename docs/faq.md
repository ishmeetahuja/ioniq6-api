# FAQ

**Can it unlock my car by itself?**
No. Nothing in it unlocks automatically, ever. Unlock needs your press-and-hold in the app, or a
tap on a notification that first asks for your phone's fingerprint or PIN. The phone token used
for walk-away can't unlock at all.

**Does it work in the US or Europe?**
Not yet. It speaks BlueLink **Canada**'s API (mybluelink.ca). The US and Europe use different
ones. Pull requests welcome.

**Does it work with my Kona / Ioniq 5 / Tucson…?**
Lock, climate and status should work with any BlueLink Canada car; walk-away lock doesn't depend
on the model. It's only been tested on an IONIQ 6, so please report how it goes. If your account
has several cars, set `BLUELINK_VIN` in the server's `.env`.

**iPhone?**
No. iOS doesn't let a sideloaded app keep watching a Bluetooth device in the background like this.

**Why do I need a server? Can't the app talk to Hyundai directly?**
It could, but the server is what makes it reliable: it keeps one long-lived sign-in (Hyundai
rate-limits sign-ins hard), confirms that locks went through, sends notifications, and locks on
its own if your phone dies after you park.

**Will this drain my car's 12 V battery?**
No more than the official app. Routine checks read Hyundai's saved copy, which doesn't wake the car,
and automatic car wake-ups are capped at 4 a day.

**Will Hyundai ban my account?**
It uses the same web API as the MyHyundai website, and the server is deliberately gentle with it.
Hyundai can still change or restrict the API at any time. See the disclaimer in the README.

**How accurate is the speed gauge?**
It's your phone's GPS speed, a second or two behind the car. Use the car's speedometer to drive.

**What does it cost to run?**
The tag and power bank are about $25 once. The server is free on hardware you own (a Raspberry Pi
uses a few watts), or a few dollars a month in the cloud.

**Is my data sent anywhere?**
Only between your phone, your server and Hyundai. See [PRIVACY.md](../PRIVACY.md).
