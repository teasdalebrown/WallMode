#!/usr/bin/env python3
"""Focused YAML/Jinja contract checks; not Home Assistant schema or runtime validation.

Run: python3 homeassistant/test_blueprints.py [--discovery discovery.json]
Uses PyYAML and Jinja2, with no Home Assistant instance or MQTT writes.
"""

import argparse
import json
from pathlib import Path
import re
from types import SimpleNamespace
from urllib.parse import urlsplit

import jinja2
from jinja2.nativetypes import NativeEnvironment
from jinja2.sandbox import ImmutableSandboxedEnvironment
import yaml


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = (ROOT / "app/src/main/java/io/github/rvbcrs/wallmode/MqttManager.kt").read_text()
LIMITS = {name: int(value.replace("_", "")) for name, value in
          re.findall(r"const val (\w+) = ([\d_]+)\b", CONTRACT)}
class NativeSandbox(ImmutableSandboxedEnvironment, NativeEnvironment):
    pass


ENV = ImmutableSandboxedEnvironment(undefined=jinja2.StrictUndefined)
ENV.filters["to_json"] = lambda value, ensure_ascii=False: json.dumps(value, ensure_ascii=ensure_ascii)
ENV.tests["match"] = lambda value, pattern: re.match(pattern, value) is not None
ENV.tests["search"] = lambda value, pattern: re.search(pattern, value) is not None
NATIVE = NativeSandbox(undefined=jinja2.StrictUndefined)
NATIVE.filters.update(ENV.filters)
NATIVE.tests.update(ENV.tests)


class Input(str):
    pass


class BlueprintLoader(yaml.SafeLoader):
    pass


BlueprintLoader.add_constructor("!input", lambda loader, node: Input(loader.construct_scalar(node)))


def substitute(value, inputs):
    if isinstance(value, Input):
        return inputs[value]  # Missing input references must fail, not become empty text.
    if isinstance(value, dict):
        return {key: substitute(item, inputs) for key, item in value.items()}
    if isinstance(value, list):
        return [substitute(item, inputs) for item in value]
    return value


def nodes(value):
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from nodes(item)
    elif isinstance(value, list):
        for item in value:
            yield from nodes(item)


def render(value, variables, native=False):
    if not isinstance(value, str) or not ("{{" in value or "{%" in value):
        return value
    result = (NATIVE if native else ENV).from_string(value).render(variables)
    return result.strip() if isinstance(result, str) else result


def check_payload(raw):
    assert len(raw.encode("utf-16-le")) // 2 <= LIMITS["MAX_COMMAND_LENGTH"]
    assert len(raw.encode()) <= LIMITS["MAX_COMMAND_LENGTH"] * 4
    payload = json.loads(raw)
    if "text" in payload:
        assert 1 <= len(payload["text"].strip().encode("utf-16-le")) // 2 <= 255
        assert not re.search(r"[\x00-\x1f\x7f-\x9f]", payload["text"])
        assert type(payload.get("volume", 80)) is int and 0 <= payload.get("volume", 80) <= 100
        return payload
    assert payload["action"] in ("show", "clear")
    assert re.fullmatch(r"[A-Za-z0-9_-]{1,64}", payload["id"])
    if payload["action"] == "show":
        assert type(payload["ttl"]) is int
        assert LIMITS["MIN_TAKEOVER_SECONDS"] <= payload["ttl"] <= LIMITS["MAX_TAKEOVER_SECONDS"]
        assert type(payload["priority"]) is int
        assert LIMITS["MIN_TAKEOVER_PRIORITY"] <= payload["priority"] <= LIMITS["MAX_TAKEOVER_PRIORITY"]
        if payload.get("kind") == "card":
            for key, limit in (("title", "MAX_CARD_TITLE_LENGTH"), ("message", "MAX_CARD_MESSAGE_LENGTH")):
                assert len(payload[key].encode("utf-16-le")) // 2 <= LIMITS[limit]
            assert 1 <= len(payload["actions"]) <= LIMITS["MAX_CARD_ACTIONS"]
            for action in payload["actions"]:
                assert re.fullmatch(r"[A-Za-z0-9_-]{1,32}", action["id"])
                assert 1 <= len(action["label"].encode("utf-16-le")) // 2 <= LIMITS["MAX_CARD_ACTION_LABEL_LENGTH"]
        else:
            url = urlsplit(payload["url"])
            assert url.scheme in ("http", "https") and url.hostname and url.username is None
    return payload


def check_notify(discovery_path):
    template = re.search(r'ANNOUNCEMENT_COMMAND_TEMPLATE = "(.*)"', CONTRACT).group(1)
    if discovery_path:
        discovery = json.loads(Path(discovery_path).read_text())
        components = discovery.get("components", discovery.get("cmps", {}))
        notify = [item for item in components.values() if item.get("platform", item.get("p")) == "notify"]
        assert len(notify) == 1
        component = notify[0]
        assert component["command_template"] == template
        assert component["retain"] is False and component["qos"] == LIMITS["QOS"]
        assert re.fullmatch(r"wallmode/[a-z0-9]{1,16}/command/announce", component["command_topic"])
        assert component["unique_id"]
    for message in ('De wasmachine is klaar', 'Hij zegt "hallo" \\ keuken — café 🌤️', '{"text":"geen injectie"}'):
        assert check_payload(render(template, {"value": message})) == {"text": message}


def blueprint(name, overrides=None, identifiers=(("mqtt", "wallmode_c0ffee12abcd3456"),)):
    path = ROOT / "homeassistant/blueprints/automation/wallmode" / f"{name}.yaml"
    source = yaml.load(path.read_text(), Loader=BlueprintLoader)
    metadata = source["blueprint"]
    assert metadata["domain"] == "automation" and metadata["name"]
    assert re.fullmatch(r"\d{4}\.\d+\.\d+", metadata["homeassistant"]["min_version"])
    inputs = {key: value["default"] for key, value in metadata["input"].items() if "default" in value}
    inputs.update(wallmode_device="tablet", triggers=[{"trigger": "event", "event_type": "wallmode_test"}],
                  title='Vraag "één" \\ kamer', message='Hallo "keuken" \\ café 🌤️',
                  view_url="https://example.test/camera?room=kitchen&view=live",
                  announcement='Deurbel "voor" \\ café 🌤️',
                  first_actions=[{"action": "persistent_notification.create", "data": {"message": "first"}}],
                  second_actions=[{"action": "persistent_notification.create", "data": {"message": "second"}}])
    inputs.update(overrides or {})
    assert source["triggers"] == Input("triggers") and source["conditions"] == Input("conditions")
    assert metadata["input"]["wallmode_device"]["selector"]["device"]["filter"] == [{"integration": "mqtt"}]
    for field in metadata["input"].values():
        assert len(field["selector"]) == 1
    config = substitute(source, inputs)
    variables = {"device_attr": lambda device, attr: identifiers if device == "tablet" else None,
                 "device_entities": lambda device: ["notify.tablet_announcement"] if device == "tablet" else [],
                 "context": SimpleNamespace(id="01K4C3P7FQK1JXSNRF3VZW819X")}
    for key, value in config["variables"].items():
        variables[key] = render(value, variables, native=True)
    for step in config["actions"]:
        for key, value in step.get("variables", {}).items():
            variables[key] = render(value, variables, native=True)
    return config, variables


def blocked(config, variables):
    # Inspect only explicit top-level validation guards, not the automation engine.
    return any(all(render(condition["value_template"], variables, native=True) is True
                   for condition in step["if"])
               for step in config["actions"] if "if" in step
               and any(item.get("error") is True for item in step.get("then", [])))


def check_blueprints():
    for name in ("announce", "event_view", "action_card"):
        config, variables = blueprint(name)
        assert not blocked(config, variables), name
        assert variables["device_identifier"] == "wallmode_c0ffee12abcd3456"
        assert config["actions"][0]["then"][0]["error"] is True
        for identifiers in (None, [], [("other", "wallmode_abcd")], [("mqtt", "other_device")],
                            [("mqtt", "wallmode_../../other")],
                            [("mqtt", "wallmode_abcd"), ("mqtt", "wallmode_ef01")]):
            invalid, context = blueprint(name, identifiers=identifiers)
            assert context["device_identifier"] == "" and blocked(invalid, context), (name, identifiers)
        assert blocked(*blueprint(name, {"wallmode_device": "deleted-device"}))
        for command in (item for item in nodes(config["actions"]) if item.get("action") == "mqtt.publish"):
            data = command["data"]
            assert data["qos"] == LIMITS["QOS"] and data["retain"] is False
            assert render(data["topic"], variables).startswith("wallmode/c0ffee12abcd3456/command/")
            check_payload(render(data["payload"], variables))

    announce, variables = blueprint("announce")
    send = announce["actions"][-1]
    assert send["action"] == "notify.send_message" and send["target"] == {"device_id": "tablet"}
    assert render(send["data"]["message"], variables) == variables["message"].strip()
    for message in ("", "x" * 256, "🌤" * 128, "bad\nmessage", "bad\x85message"):
        assert blocked(*blueprint("announce", {"message": message})), repr(message)

    for name in ("event_view", "action_card"):
        for changes in ({"duration": 4}, {"duration": 601}, {"priority": -1}, {"priority": 101}):
            assert blocked(*blueprint(name, changes)), (name, changes)
        for duration in (5, 600):
            config, variables = blueprint(name, {"duration": duration})
            assert not blocked(config, variables)
            assert variables["view_payload" if name == "event_view" else "card_payload"]["ttl"] == duration
    for url in ("rtsp://example.test/live", "file:///etc/passwd", "https://user:pass@example.test/", "https://"):
        assert blocked(*blueprint("event_view", {"view_url": url})), url
    assert blocked(*blueprint("event_view", {"view_url": "https://example.test/" + "a" * 2048}))
    assert blocked(*blueprint("event_view", {"announcement": "x" * 256}))
    assert not blocked(*blueprint("event_view", {"announcement": ""}))
    for volume in (-1, 101):
        assert blocked(*blueprint("event_view", {"volume": volume}))
    for changes in ({"title": ""}, {"title": "x" * 81}, {"message": "x" * 301},
                    {"first_label": ""}, {"second_label": "x" * 25}, {"title": "bad\ntext"},
                    {"title": "🌤" * 41}, {"first_label": "🌤" * 13}):
        assert blocked(*blueprint("action_card", changes)), changes

    card, variables = blueprint("action_card", {"first_label": 'Ja "één" \\', "second_label": "Nee 🌤️"})
    assert not blocked(card, variables)
    payload = variables["card_payload"]
    assert payload["title"] == variables["title"] and payload["actions"][0]["label"] == variables["first_label"]
    assert payload["id"] == "wm_card_" + variables["context"].id
    waiter = next(item for item in nodes(card["actions"]) if "wait_for_trigger" in item)
    assert waiter["continue_on_timeout"] is False
    parallel = next(item["parallel"] for item in card["actions"] if "parallel" in item)
    publisher = next(branch["sequence"] for branch in parallel
                     if any(step.get("action") == "mqtt.publish" for step in branch["sequence"]))
    delay = int(render(publisher[0]["delay"]["seconds"], variables))
    assert delay >= 1 and publisher[1]["action"] == "mqtt.publish"
    assert int(render(waiter["timeout"]["seconds"], variables)) == payload["ttl"] + delay
    assert card["mode"] == "single"  # No overlapping question runs using the same tablet.
    for trigger in waiter["wait_for_trigger"]:
        expected = render(trigger["payload"], variables)
        assert render(trigger["topic"], variables) == "wallmode/c0ffee12abcd3456/event/action"
        for reply in ({"card_id": payload["id"], "action_id": trigger["id"]},
                      {"card_id": "wm_card_OLD_RUN", "action_id": trigger["id"]},
                      {"card_id": payload["id"], "action_id": "unknown"}, {}, [], None):
            # MQTT value templates receive message fields, not run-scoped variables.
            actual = render(trigger["value_template"], {"value_json": reply})
            assert (actual == expected) == (reply == {"card_id": payload["id"], "action_id": trigger["id"]})
        assert render(trigger["value_template"], {}) != expected
    choices = next(item["choose"] for item in nodes(card["actions"]) if "choose" in item)
    for answer in ("first", "second"):
        matches = [item for item in choices if render(item["conditions"], {"wait": {"trigger": {"id": answer}}}, native=True)]
        assert len(matches) == 1 and matches[0]["sequence"][0]["data"]["message"] == answer


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--discovery", help="Optional actual device-based MQTT discovery JSON")
    args = parser.parse_args()
    check_notify(args.discovery)
    check_blueprints()
    print("PASS: 3 blueprint input/template/payload checks and notify JSON escaping; not HA runtime validation")
