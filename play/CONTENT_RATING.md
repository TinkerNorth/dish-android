# IARC content rating answers for Dish

Quick reference for filling out Play's IARC questionnaire. Dish is a utility-class app (input forwarding); it has no game content of its own.

| Question category | Answer | Notes |
|---|---|---|
| Violence | NONE | Dish is a controller. What gets played through it is the host PC's content, not Dish's. |
| Sexuality / nudity | NONE | |
| Language / profanity | NONE | |
| Controlled substances | NONE | |
| User-generated content | NONE | No social features, no user accounts. |
| User-to-user communication | INDIRECT (see below) | Dish has no chat, no comments and no messaging of its own. It can carry the user's voice to their own PC as the emulated controller's microphone, where apps already on that PC (party chat, Discord, a game) may transmit it. |
| Sharing user location | NONE | No location permissions. |
| Personal info sharing | NONE | No personal data collected. |
| Digital purchases | NONE | Free, open source. No IAP. |
| Gambling references | NONE | |

## The controller microphone, in the questionnaire's terms

Dish can act as the emulated controller's own microphone: with the per-controller Microphone switch on, the phone's microphone stands in for the endpoint a real DualShock 4 v2 or DualSense presents to a PC. Answer the communication questions on that basis:

- **Does the app allow users to communicate with each other?** Not by itself. Dish transmits nothing between Dish users, has no directory, no accounts, no rooms and no server. What it does is put the user's voice on their own PC's microphone input, where software the user already runs may then transmit it. If the questionnaire has no "indirect" option, answer YES and use the explanation above rather than claiming NONE.
- **Is the communication moderated or filtered?** Not applicable. Dish neither receives nor stores the audio it forwards, and there is nowhere for it to be moderated. Whatever moderation applies is the PC application's.
- **Is it audio, video or text?** Audio only, and only in the one direction, from the phone to the user's own paired PC on their own local network.
- **Is it optional?** Yes, and off by default. It needs the runtime microphone permission, a per-controller switch, a live Satellite session, and no active mute.

This does not change the age bands: nothing Dish itself shows or transmits is age-sensitive, and the disclosure exists so the listing's interactive-elements line is honest.

Expected rating across boards: **3+ / Everyone / PEGI 3 / Apple 4+ equivalent**, with an interactive-elements note for user interaction where the board asks for one.
