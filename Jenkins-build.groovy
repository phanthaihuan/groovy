pipeline {
    agent any

    parameters {
        string(name: 'MILU2_INFRA_GIT_BRANCH', defaultValue: 'main', description: 'Git branch storing Terraform.')
    }

    environment {
        AWS_ACCESS_KEY_ID     = credentials('aws-secret-key-id')
        AWS_SECRET_ACCESS_KEY = credentials('aws-secret-access-key')
        AWS_REGION    = 'ap-northeast-1'
        GIT_CREDENTIAL = 'github-access'
        INSTALLER_DIR = 'terraform'
        TF_VAR_SSH_PRIVATE_KEY = credentials('SSH_PRIVATE_KEY')
        ANSIBLE_HOST_KEY_CHECKING = 'False'
    }

    stages {
        stage('Checkout SCM') {
            steps {
                deleteDir()
                dir(INSTALLER_DIR) {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: MILU2_INFRA_GIT_BRANCH]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CleanBeforeCheckout']],
                        submoduleCfg: [],
                        userRemoteConfigs: [
                            [
                                credentialsId: GIT_CREDENTIAL,
                                url: 'git@github.com:phanthaihuan/terraform2024.git'
                            ]
                        ]
                    ])
                }
            }
        }

        stage('Init Provider') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'pwd'
                    sh 'ls -lrth'
                    sh 'terraform init -no-color'
                }
            }
        }

        stage('Validate configuration files') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'terraform validate -no-color'
                    sh 'if [ $? -ne 0 ]; then exit 1; fi'
                }
            }
        }

        stage('Plan Resources') {
            steps {
                dir(INSTALLER_DIR) {
                    sh """
                        terraform plan -no-color \
                        -var 'SSH_PRIVATE_KEY=${TF_VAR_SSH_PRIVATE_KEY}' \
                        -var 'AWS_REGION=${AWS_REGION}'
                    """
                }
            }
        }

        stage('Apply Resources') {
            steps {
                script {
                    dir(INSTALLER_DIR) {
                        def userInput = input(
                            id: 'userInput',
                            message: 'Do you want to proceed for production deployment?',
                            parameters: [
                                string(
                                    name: 'INPUT_VALUE',
                                    defaultValue: '',
                                    description: 'Please enter YES or NO'
                                )
                            ]
                        )

                        if (userInput.trim() == 'YES') {
                            sh """
                                terraform apply -auto-approve -no-color \
                                -var 'SSH_PRIVATE_KEY=${TF_VAR_SSH_PRIVATE_KEY}' \
                                -var 'AWS_REGION=${AWS_REGION}'
                            """
                        } else {
                            currentBuild.result = 'ABORTED'
                            return
                        }
                    }
                }
            }
        }

        stage('Print Inventory') {
            steps {
                dir(INSTALLER_DIR) {
                    sh '''
                        echo $(terraform output -json ec2_public_ip) | awk -F'"' '{print $2}' > ansible/aws_hosts
                        cat ansible/aws_hosts
                    '''
                }
            }
        }

        stage('Wait EC2') {
            steps {
                dir(INSTALLER_DIR) {
                    sh '''
                        instance_id=$(terraform output -json ec2_instance_id | awk -F'"' '{print $2}')
                        aws ec2 wait instance-status-ok --region ${AWS_REGION} --instance-ids $instance_id
                    '''
                }
            }
        }

        stage('Ansible approval') {
            input {
                message 'Do you want to proceed for Ansible deployment?'
                ok 'Yes'
            }
            steps {
                echo "Ansible deployment is in progress..."
            }
        }

        stage('Deploy Ansible') {
            steps {
                dir(INSTALLER_DIR) {
                    sh """
                        cd ansible
                        ansible-playbook -v -i aws_hosts playbook.yaml --private-key ${TF_VAR_SSH_PRIVATE_KEY}
                    """
                }
            }
        }
    }

    post {
        success {
            echo 'Deployment is successful.'
        }

        failure {
            echo 'Deployment is failed.'
            dir(INSTALLER_DIR) {
                sh """
                    terraform destroy -auto-approve -no-color \
                    -var 'SSH_PRIVATE_KEY=${TF_VAR_SSH_PRIVATE_KEY}' \
                    -var 'AWS_REGION=${AWS_REGION}'
                """
            }
        }

        aborted {
            echo 'Deployment is aborted.'
            dir(INSTALLER_DIR) {
                sh """
                    terraform destroy -auto-approve -no-color \
                    -var 'SSH_PRIVATE_KEY=${TF_VAR_SSH_PRIVATE_KEY}' \
                    -var 'AWS_REGION=${AWS_REGION}'
                """
            }            
        }
    }
}
