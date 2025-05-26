import argparse
from datetime import datetime, timedelta
from confluence_lib import *
from httplib2 import Http
from json import dumps
from bs4 import BeautifulSoup


def routine(confluence_token: str, target_page: int, text_column_name: str, env_column_name: str, expiry_date_limit: str,
            non_prod_webhook_url: str, prod_webhook_url: str, expiry_date_column_name: str, send_chat_message: bool,
            recursive: bool):
    link_text = f"\n<https://confluence.airbus.corp/pages/viewpage.action?pageId={target_page}|Confluence page>"
    days_before_notification = int(expiry_date_limit)
    if not days_before_notification or days_before_notification == 0:
        raise Exception(f"I could not convert {expiry_date_limit} to a number")

    today = datetime.today().date()
    nonprod_columns = ["nonprod", "dev", "int", "val"]

    if recursive:
        # Manual run: alert on all expiring on or before trigger_date
        trigger_date = today + timedelta(days=days_before_notification)
    else:
        # Scheduled run: alert only for exactly 14 and 2 days before expiry
        trigger_date_14 = today + timedelta(days=14)
        trigger_date_2 = today + timedelta(days=2)

    infos = get_page_content_and_version(confluence_token, target_page)
    soup = BeautifulSoup(infos.get("content"), 'html.parser')

    table = soup.find('tbody')
    header_cells = table.find_all('th')
    headers = [header.text.strip() for header in header_cells if header]

    header_index_map = {header: index for index, header in enumerate(headers)}

    title_idx = header_index_map.get(text_column_name)
    environment_idx = header_index_map.get(env_column_name)
    next_expiration_idx = header_index_map.get(expiry_date_column_name)

    prod_message = ""
    non_prod_message = ""

    table_rows = table.find_all('tr')[1:]  # skip header row

    for tr in table_rows:
        cells = tr.find_all('td')

        if title_idx is not None:
            title = cells[title_idx].text.strip()
        else:
            raise Exception(f"I could not find the column with name/title {text_column_name}")

        if environment_idx is not None:
            environment = cells[environment_idx].text.strip().lower()
            # remove any color tags from environment string
            for r in (("blue", ""), ("grey", ""), ("yellow", ""), ("green", "")):
                environment = environment.replace(*r)
        else:
            raise Exception(f"I could not find the column with name/title {env_column_name}")

        if next_expiration_idx is not None:
            expiry_date_html = cells[next_expiration_idx].find('time')
            if expiry_date_html and 'datetime' in expiry_date_html.attrs:
                date_string = expiry_date_html['datetime']
                date = datetime.strptime(date_string, '%Y-%m-%d').date()
                exp_string = "is expiring on"
                if date < today:
                    exp_string = "has expired on"

                if recursive:
                    # Manual mode: include all expiring on or before trigger_date
                    if date <= trigger_date:
                        message = f"`{title}` {exp_string}: *{date.strftime('%d.%m.%Y')}*\n"
                        if environment in nonprod_columns:
                            non_prod_message += message
                        else:
                            prod_message += message
                else:
                    # Scheduled mode: include only exactly 14 or 2 days after today
                    if date == trigger_date_14 or date == trigger_date_2:
                        message = f"`{title}` {exp_string}: *{date.strftime('%d.%m.%Y')}*\n"
                        if environment in nonprod_columns:
                            non_prod_message += message
                        else:
                            prod_message += message

    if non_prod_message and send_chat_message:
        non_prod_message = "*NONPROD*\n" + non_prod_message + link_text
        send_message(non_prod_message, non_prod_webhook_url)

    if prod_message and send_chat_message:
        prod_message = "*PROD*\n" + prod_message + link_text
        send_message(prod_message, prod_webhook_url)


def send_message(text: str, webhook_url: str):
    """Send message to Google Chat webhook."""
    app_message = {"text": text}
    message_headers = {"Content-Type": "application/json; charset=UTF-8"}
    http_obj = Http()
    response = http_obj.request(
        uri=webhook_url,
        method="POST",
        headers=message_headers,
        body=dumps(app_message),
    )
    status_code = int(response[0].get('status', 0))
    if status_code != 200:
        raise Exception(f"Sending the message to the webhook failed with status {status_code}")


def main():
    parser = argparse.ArgumentParser(description="Update Certificate Inventory Page", add_help=True)
    required_arguments = parser.add_argument_group('required arguments')
    required_arguments.add_argument('-c', '--confluence-token', required=True, help="confluence token")
    required_arguments.add_argument('--env-column-name', required=True, help="column title of the environment column")
    required_arguments.add_argument('--text-column-name', required=True, help="column title of the text column")
    required_arguments.add_argument('--expiry-date-limit', required=True, help="days before expiry for notification")
    required_arguments.add_argument('--target-page', required=True, help="confluence page id")
    required_arguments.add_argument('--non-prod-webhook-url', required=True, help="URL of google chat webhook")
    required_arguments.add_argument('--prod-webhook-url', required=True, help="URL of google chat webhook")
    required_arguments.add_argument('--expiry-date-column-name', required=True, help="column header for expiry date")
    parser.add_argument('--send-chat-message', default="true", help="Set to false to disable sending")
    parser.add_argument('--recursive', default="false", help="Set to true to get all expiring up to expiry date limit")

    args = parser.parse_args()
    routine(
        confluence_token=args.confluence_token,
        target_page=args.target_page,
        non_prod_webhook_url=args.non_prod_webhook_url,
        prod_webhook_url=args.prod_webhook_url,
        expiry_date_column_name=args.expiry_date_column_name,
        text_column_name=args.text_column_name,
        env_column_name=args.env_column_name,
        expiry_date_limit=args.expiry_date_limit,
        send_chat_message=True if args.send_chat_message.lower() == "true" else False,
        recursive=True if args.recursive.lower() == "true" else False
    )


if __name__ == '__main__':
    main()
